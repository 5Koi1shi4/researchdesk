// @vitest-environment jsdom
import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,expect,it,vi} from 'vitest';
import {DocumentPage} from './DocumentPage';
const project={id:'project-1',name:'索引研究',createdAt:'2026-10-12T00:00:00Z'};
const session={user:{id:'user-1',email:'user@example.test'},csrfToken:'csrf-1'};
const doc={documentId:'doc-1',versionId:'v-1',taskId:'t-1',title:'研究.md',state:'PROCESSING',errorCode:null,errorMessage:null,sizeBytes:20,pageCount:null,createdAt:'2026-10-12T00:00:00Z'};
const response=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.useRealTimers();});
it('retries a transient failed task with csrf and then refreshes persisted processing state',async()=>{
 let retried=false;
 vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{
  if(options.method==='POST'){expect(path).toBe('/api/projects/project-1/tasks/t-1/retry');expect(new Headers(options.headers).get('X-CSRF-Token')).toBe('csrf-1');retried=true;return response({taskId:'t-1',documentVersionId:'v-1',state:'QUEUED',errorCode:null,errorMessage:null},202);}
  return response([{...doc,state:retried?'PROCESSING':'FAILED',errorCode:retried?null:'INGESTION_UNAVAILABLE'}]);
 }));
 render(<DocumentPage project={project} session={session}/>);
 fireEvent.click(await screen.findByRole('button',{name:'重试处理'}));
 await screen.findByText('处理中');expect(screen.queryByText('处理失败')).toBeNull();
});
it('uploads multipart with disclosure and only renders returned persisted state',async()=>{
 let uploaded=false;
 vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{
  if(options.method==='POST'){expect(options.body).toBeInstanceOf(FormData);expect(new Headers(options.headers).get('Content-Type')).toBeNull();expect(new Headers(options.headers).get('X-CSRF-Token')).toBe('csrf-1');uploaded=true;return response({documentId:'doc-1',versionId:'v-1',taskId:'t-1',state:'PROCESSING'},201);}
  return response(uploaded?[doc]:[]);
 }));
 render(<DocumentPage project={project} session={session}/>);
 await screen.findByText('上传第一份资料');
 expect(screen.getByText(/配置的模型服务/)).toBeTruthy();
 fireEvent.change(screen.getByLabelText('资料文件'),{target:{files:[new File(['# 研究'],'研究.md',{type:'text/markdown'})]}});
 fireEvent.click(screen.getByRole('button',{name:'上传资料'}));
 await screen.findByText('研究.md');expect(screen.getByText('处理中')).toBeTruthy();expect(screen.queryByText('可用于研究')).toBeNull();
});
it('shows server failure and recovery without adding a fake document',async()=>{
 vi.stubGlobal('fetch',vi.fn(async(_path:string,options:RequestInit)=>options.method==='POST'?response({code:'ENCRYPTED_PDF',message:'不支持加密 PDF，请解密后重新上传。',requestId:'r-1'},400):response([])));
 render(<DocumentPage project={project} session={session}/>);
 await screen.findByText('上传第一份资料');
 fireEvent.change(screen.getByLabelText('资料文件'),{target:{files:[new File(['secret'],'秘密.pdf')]}});
 fireEvent.click(screen.getByRole('button',{name:'上传资料'}));
 expect((await screen.findByRole('alert')).textContent).toContain('解密后重新上传');
 expect(screen.queryByText('秘密.pdf')).toBeNull();expect((screen.getByRole('button',{name:'上传资料'}) as HTMLButtonElement).disabled).toBe(false);
});
it('discards a delayed private list when project changes',async()=>{
 let resolve:(r:Response)=>void=()=>{};
 vi.stubGlobal('fetch',vi.fn((path:string)=>path.includes('project-1')?new Promise<Response>(r=>resolve=r):Promise.resolve(response([]))));
 const view=render(<DocumentPage project={project} session={session}/>);
 view.rerender(<DocumentPage project={{...project,id:'project-2'}} session={session}/>);
 await screen.findByText('上传第一份资料');
 await act(async()=>resolve(response([doc])));
 expect(screen.queryByText('研究.md')).toBeNull();
});
it('refreshes persisted failure and renders its reason',async()=>{
 vi.stubGlobal('fetch',vi.fn(async()=>response([{...doc,state:'FAILED',errorCode:'PARSE_FAILED',errorMessage:'资料无法解析，请检查原文件后重新上传。'}])));
 render(<DocumentPage project={project} session={session}/>);
 await screen.findByText('研究.md');expect(screen.getByText('处理失败')).toBeTruthy();expect(screen.getByText(/检查原文件/)).toBeTruthy();
 fireEvent.click(screen.getByRole('button',{name:'刷新资料状态'}));await waitFor(()=>expect(screen.getByText('处理失败')).toBeTruthy());
});
it('keeps upload rejection visible through successful processing polls until a new file is selected',async()=>{
 vi.useFakeTimers({toFake:['setInterval','clearInterval']});
 let listReads=0;
 vi.stubGlobal('fetch',vi.fn(async(_path:string,options:RequestInit)=>{
  if(options.method==='POST')return response({code:'ENCRYPTED_PDF',message:'不支持加密 PDF，请解密后重新上传。',requestId:'r-1'},400);
  listReads++;return response([{...doc,title:listReads===1?'研究.md':'刷新后的研究.md'}]);
 }));
 render(<DocumentPage project={project} session={session}/>);
 await screen.findByText('研究.md');
 fireEvent.change(screen.getByLabelText('资料文件'),{target:{files:[new File(['secret'],'秘密.pdf')]}});
 fireEvent.click(screen.getByRole('button',{name:'上传资料'}));
 expect((await screen.findByRole('alert')).textContent).toContain('解密后重新上传');
 await act(async()=>{await vi.advanceTimersByTimeAsync(3000);});
 expect(screen.getByText('刷新后的研究.md')).toBeTruthy();
 expect(screen.getByRole('alert').textContent).toContain('解密后重新上传');
 fireEvent.change(screen.getByLabelText('资料文件'),{target:{files:[new File(['# 修正资料'],'修正资料.md')]}});
 expect(screen.queryByRole('alert')).toBeNull();
});
it('confirms deletion, revokes preview and refreshes the server state',async()=>{
 let deleted=false;
 vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{
  if(options.method==='DELETE'){expect(path).toBe('/api/documents/doc-1');expect(new Headers(options.headers).get('X-CSRF-Token')).toBe('csrf-1');deleted=true;return response({documentId:'doc-1',state:'DELETING'},202);}
  if(path.endsWith('/source-info'))return response({documentId:'doc-1',documentVersionId:'v-1',title:doc.title,mediaType:'text/markdown',pageCount:null,availability:'AVAILABLE'});
  if(path.endsWith('/source'))return new Response('Original private source');
  return response([{...doc,state:deleted?'DELETING':'READY'}]);
 }));
 render(<DocumentPage project={project} session={session}/>);
 fireEvent.click(await screen.findByRole('button',{name:'预览原文'}));await screen.findByText('Original private source');
 fireEvent.click(screen.getByRole('button',{name:'删除资料'}));
 expect(screen.getByText(/历史回答正文仍会保留/)).toBeTruthy();expect(deleted).toBe(false);
 fireEvent.click(screen.getByRole('button',{name:'确认删除'}));
 await screen.findByText('正在删除');expect(screen.queryByText('Original private source')).toBeNull();expect(screen.queryByRole('button',{name:'预览原文'})).toBeNull();
});
it('cancels deletion without sending a mutation',async()=>{
 const fetch=vi.fn(async()=>response([{...doc,state:'READY'}]));vi.stubGlobal('fetch',fetch);
 render(<DocumentPage project={project} session={session}/>);
 fireEvent.click(await screen.findByRole('button',{name:'删除资料'}));fireEvent.click(screen.getByRole('button',{name:'取消删除'}));
 expect(screen.queryByRole('button',{name:'确认删除'})).toBeNull();expect(fetch.mock.calls.length).toBe(1);
});
it('moves focus into preview so immediate Escape closes it and restores the opener',async()=>{
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.endsWith('/source-info')?response({documentId:'doc-1',documentVersionId:'v-1',title:doc.title,mediaType:'text/markdown',pageCount:null,availability:'AVAILABLE'}):path.endsWith('/source')?new Response('Keyboard source'):response([{...doc,state:'READY'}])));
 render(<DocumentPage project={project} session={session}/>);
 const opener=await screen.findByRole('button',{name:'预览原文'});opener.focus();fireEvent.click(opener);
 const close=screen.getByRole('button',{name:'关闭原文'});expect(document.activeElement).toBe(close);
 fireEvent.keyDown(document.activeElement!,{key:'Escape'});
 expect(screen.queryByRole('region',{name:'原文预览'})).toBeNull();expect(document.activeElement).toBe(opener);
});
