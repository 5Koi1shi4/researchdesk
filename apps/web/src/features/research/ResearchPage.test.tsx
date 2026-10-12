// @vitest-environment jsdom
import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,expect,it,vi} from 'vitest';
import {ResearchPage} from './ResearchPage';
const project={id:'p1',name:'研究',createdAt:'2026-10-12T00:00:00Z'};
const session={user:{id:'u1',email:'u@test.dev'},csrfToken:'csrf'};
const citation={chunkId:'c1',documentVersionId:'v1',title:'资料.md',page:null,paragraph:1,excerpt:'原文证据',availability:'AVAILABLE'};
const snapshot={runId:'r1',projectId:'p1',conversationId:'conv1',kind:'ANSWER',documentVersionIds:['v1'],prompt:'问题',state:'QUEUED',text:'',citations:[],lastSeq:0,mode:'DEMO',error:null};
const response=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});
const events=(values:unknown[])=>new Response(new ReadableStream({start(c){for(const e of values)c.enqueue(new TextEncoder().encode('data: '+JSON.stringify(e)+'\n\n'));c.close();}}),{headers:{'Content-Type':'text/event-stream'}});
const ev=(seq:number,type:string,payload:unknown)=>({runId:'r1',seq,type,payload});
const docs=[{documentId:'d1',versionId:'v1',title:'资料.md',state:'READY'},{documentId:'d2',versionId:'v2',title:'处理中的资料',state:'PROCESSING'}];
afterEach(()=>{cleanup();vi.unstubAllGlobals();window.history.replaceState({},'', '/');});
async function start(){render(<ResearchPage project={project} session={session}/>);fireEvent.click(await screen.findByLabelText('资料.md'));fireEvent.change(screen.getByLabelText('研究问题'),{target:{value:'问题'}});fireEvent.click(screen.getByRole('button',{name:'开始研究'}));}
it('replays missing events with Last-Event-ID and shows verified selectable evidence',async()=>{
 let streams=0;const cursors:string[]=[];let posts=0;
 vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit={})=>{
  if(path.endsWith('/documents'))return response(docs);
  if(path.startsWith('/api/conversations/'))return response({conversationId:'conv1',projectId:'p1',runs:[]});
  if(path==='/api/runs'){posts++;expect(JSON.parse(String(options.body))).toEqual({kind:'ANSWER',projectId:'p1',documentVersionIds:['v1'],prompt:'问题'});expect(new Headers(options.headers).get('X-CSRF-Token')).toBe('csrf');return response(snapshot,201);}
  if(path.endsWith('/events')){cursors.push(new Headers(options.headers).get('Last-Event-ID')!);return ++streams===1?events([ev(1,'started',{kind:'ANSWER'}),ev(3,'delta',{text:'B'})]):events([ev(2,'delta',{text:'A'}),ev(3,'delta',{text:'B'}),ev(4,'citation',{citation}),ev(5,'completed',{state:'COMPLETED'})]);}
  throw Error('unexpected '+path);
 }));
 await start();await screen.findByText('AB');expect(await screen.findByText(/演示模式/)).toBeTruthy();
 fireEvent.click(await screen.findByRole('button',{name:/引用 1/}));expect(screen.getByText('原文证据')).toBeTruthy();
 expect(cursors).toEqual(['0','1']);expect(posts).toBe(1);expect((screen.getByLabelText('处理中的资料') as HTMLInputElement).disabled).toBe(true);
});
it('restores expired event window from snapshot without creating another run',async()=>{
 window.history.replaceState({},'', '/?run=r1');let posts=0,reads=0;
 vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit={})=>{
  if(options.method==='POST'){posts++;throw Error('must not generate');}
  if(path.endsWith('/documents'))return response(docs);
  if(path.startsWith('/api/conversations/'))return response({conversationId:'conv1',projectId:'p1',runs:[]});
  if(path.endsWith('/events'))return response({code:'EVENT_WINDOW_EXPIRED',message:'expired',requestId:'q'},410);
  if(path==='/api/runs/r1')return response(++reads===1?snapshot:{...snapshot,state:'COMPLETED',text:'已恢复回答',lastSeq:5,citations:[citation]});
  throw Error(path);
 }));
 render(<ResearchPage project={project} session={session}/>);await screen.findByText('已恢复回答');expect(posts).toBe(0);
});
it('shows cancellation pending and preserves cancelled draft without a success claim',async()=>{
 let resolve!:(r:Response)=>void;
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
  if(path.endsWith('/documents'))return response(docs);
  if(path.startsWith('/api/conversations/'))return response({conversationId:'conv1',projectId:'p1',runs:[]});
  if(path==='/api/runs')return response(snapshot,201);
  if(path.endsWith('/cancel'))return new Promise<Response>(r=>resolve=r);
  if(path.endsWith('/events'))return new Response(new ReadableStream({start(c){c.enqueue(new TextEncoder().encode('data: '+JSON.stringify(ev(1,'started',{kind:'ANSWER'}))+'\n\n'));}}),{headers:{'Content-Type':'text/event-stream'}});
  throw Error(path);
 }));
 await start();fireEvent.click(await screen.findByRole('button',{name:'停止生成'}));expect(screen.getByRole('button',{name:'正在请求停止…'})).toBeTruthy();expect(screen.getByText(/仍可能计费/)).toBeTruthy();
 resolve(response({...snapshot,state:'CANCELLED',text:'未完成草稿',lastSeq:2}));await screen.findByText('已停止');expect(screen.queryByText('引用已验证')).toBeNull();
});
it('failed run remains failed without automatic model retry',async()=>{
 let posts=0;
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
  if(path.endsWith('/documents'))return response(docs);
  if(path.startsWith('/api/conversations/'))return response({conversationId:'conv1',projectId:'p1',runs:[]});
  if(path==='/api/runs'){posts++;return response(snapshot,201);}
  return events([ev(1,'failed',{error:{code:'TIMEOUT',message:'供应商超时',requestId:'q1'}})]);
 }));
 await start();await screen.findByText('供应商超时');await waitFor(()=>expect(screen.getByRole('button',{name:'开始研究'})).toBeTruthy());expect(posts).toBe(1);
});
it('ignores delayed private run creation after switching project',async()=>{
 let resolve!:(response:Response)=>void;
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>path==='/api/runs'?new Promise<Response>(r=>resolve=r):response(docs)));
 const view=render(<ResearchPage project={project} session={session}/>);
 fireEvent.click(await screen.findByLabelText('资料.md'));fireEvent.change(screen.getByLabelText('研究问题'),{target:{value:'问题'}});fireEvent.click(screen.getByRole('button',{name:'开始研究'}));
 view.rerender(<ResearchPage project={{...project,id:'p2'}} session={session}/>);
 resolve(response({...snapshot,state:'COMPLETED',text:'私密答案',lastSeq:3}));
 await waitFor(()=>expect((screen.getByLabelText('研究问题') as HTMLTextAreaElement).disabled).toBe(false));
 expect(screen.queryByText('私密答案')).toBeNull();expect(window.location.search).not.toContain('r1');
});
it('renders stable citation markers as keyboard accessible inline references',async()=>{
 window.history.replaceState({},'', '/?run=r1');
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>response(path.endsWith('/documents')?docs:path.startsWith('/api/conversations/')?{conversationId:'conv1',projectId:'p1',runs:[]}:{...snapshot,state:'COMPLETED',text:'事实[[cite:c1]]。',lastSeq:3,citations:[citation]})));
 render(<ResearchPage project={project} session={session}/>);
 const inline=await screen.findByRole('button',{name:'查看引用 1'});inline.focus();expect(document.activeElement).toBe(inline);fireEvent.click(inline);
 expect(screen.getByText('原文证据')).toBeTruthy();expect(screen.queryByText(/\[\[cite:/)).toBeNull();
});
it.each([503,502])('recovers an HTTP %s stream failure without another model request',async status=>{
 let streams=0,posts=0;
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
  if(path.endsWith('/documents'))return response(docs);
  if(path.startsWith('/api/conversations/'))return response({conversationId:'conv1',projectId:'p1',runs:[]});
  if(path==='/api/runs'){posts++;return response(snapshot,201);}
  if(path.endsWith('/events'))return ++streams===1?(status===503?response({code:'UNAVAILABLE',message:'retry',requestId:'q1'},503):new Response('<html>bad gateway</html>',{status:502})):events([ev(1,'started',{kind:'ANSWER'}),ev(2,'delta',{text:'连接已恢复'}),ev(3,'completed',{state:'COMPLETED'})]);
  throw Error(path);
 }));
 await start();expect(await screen.findByText('连接已恢复',{}, {timeout:2500})).toBeTruthy();expect(posts).toBe(1);expect(streams).toBe(2);
});
it('bounds stream retries and offers explicit snapshot recovery of the same run',async()=>{
 let streams=0,posts=0;
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
  if(path.endsWith('/documents'))return response(docs);
  if(path.startsWith('/api/conversations/'))return response({conversationId:'conv1',projectId:'p1',runs:[]});
  if(path==='/api/runs'){posts++;return response(snapshot,201);}
  if(path.endsWith('/events')){streams++;return response({code:'UNAVAILABLE',message:'retry',requestId:'q1'},503);}
  if(path==='/api/runs/r1')return response({...snapshot,state:'COMPLETED',text:'持久化完成答案',lastSeq:3});
  throw Error(path);
 }));
 await start();fireEvent.click(await screen.findByRole('button',{name:'恢复当前运行'},{timeout:6500}));
 expect(await screen.findByText('持久化完成答案')).toBeTruthy();expect(streams).toBe(4);expect(posts).toBe(1);
},10000);
it('loads bounded persisted conversation history and retains earlier citations when submitting the next turn',async()=>{
 window.history.replaceState({},'', '/?run=r1');let posted=false,historyReads=0;
 const previous={...snapshot,state:'COMPLETED',text:'上一轮事实[[cite:c1]]。',lastSeq:4,citations:[citation]};
 const second={...snapshot,runId:'r2',prompt:'后续问题',state:'COMPLETED',text:'新的回答',lastSeq:3};
 vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit={})=>{
  if(path.endsWith('/documents'))return response(docs);
  if(path==='/api/runs/r1')return response(previous);
  if(path==='/api/runs'){expect(JSON.parse(String(options.body)).conversationId).toBe('conv1');posted=true;return response(second,201);}
  if(path==='/api/conversations/conv1'){
   historyReads++;return response({conversationId:'conv1',projectId:'p1',runs:[...Array.from({length:14},(_,i)=>({...snapshot,runId:'old'+i,prompt:'旧问题'+i,state:'COMPLETED',text:'旧答案'+i,lastSeq:3})),previous,...(posted?[second]:[])]});
  }
  throw Error(path);
 }));
 render(<ResearchPage project={project} session={session}/>);
 expect(await screen.findByText('旧答案13')).toBeTruthy();expect(screen.queryByText('旧答案0')).toBeNull();
 fireEvent.click(await screen.findByLabelText('资料.md'));fireEvent.change(screen.getByLabelText('研究问题'),{target:{value:'后续问题'}});fireEvent.click(screen.getByRole('button',{name:'开始研究'}));
 expect(await screen.findByText('新的回答')).toBeTruthy();expect(await screen.findByText('上一轮事实')).toBeTruthy();
 fireEvent.click(screen.getByRole('button',{name:'查看引用 1'}));expect(screen.getByText('原文证据')).toBeTruthy();expect(historyReads).toBe(2);
});
it('does not render delayed history from a previous project',async()=>{
 window.history.replaceState({},'', '/?run=r1');let resolve!:(value:Response)=>void;
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
  if(path.endsWith('/documents'))return response(docs);
  if(path==='/api/conversations/conv1')return new Promise<Response>(r=>resolve=r);
  return response({...snapshot,state:'COMPLETED',text:'当前答案',lastSeq:3});
 }));
 const view=render(<ResearchPage project={project} session={session}/>);await screen.findByText('当前答案');
 window.history.replaceState({},'', '/');view.rerender(<ResearchPage project={{...project,id:'p2'}} session={session}/>);
 resolve(response({conversationId:'conv1',projectId:'p1',runs:[{...snapshot,runId:'private',state:'COMPLETED',text:'私密历史',lastSeq:3}]}));
 await waitFor(()=>expect(screen.queryByText('当前答案')).toBeNull());expect(screen.queryByText('私密历史')).toBeNull();
});
it('does not let delayed URL hydration replace a newly created run in the same project',async()=>{
 window.history.replaceState({},'', '/?run=r1');let hydrate!:(value:Response)=>void;const submitted:unknown[]=[];
 vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit={})=>{
  if(path.endsWith('/documents'))return response(docs);
  if(path==='/api/runs/r1')return new Promise<Response>(r=>hydrate=r);
  if(path==='/api/runs'){submitted.push(JSON.parse(String(options.body)));return response({...snapshot,runId:'r2',conversationId:'conv2',prompt:'新问题',state:'COMPLETED',text:'新的运行答案',lastSeq:3});}
  if(path.startsWith('/api/conversations/'))return response({conversationId:path.split('/').pop(),projectId:'p1',runs:[]});
  throw Error(path);
 }));
 await start();await screen.findByText('新的运行答案');
 await act(async()=>hydrate(response({...snapshot,state:'COMPLETED',text:'过期恢复答案',lastSeq:3})));
 expect(screen.getByText('新的运行答案')).toBeTruthy();expect(screen.queryByText('过期恢复答案')).toBeNull();expect(new URL(window.location.href).searchParams.get('run')).toBe('r2');
 fireEvent.change(screen.getByLabelText('研究问题'),{target:{value:'继续研究'}});fireEvent.click(screen.getByRole('button',{name:'开始研究'}));
 await waitFor(()=>expect(submitted).toHaveLength(2));expect(submitted[1]).toMatchObject({conversationId:'conv2'});
});
it.each([
 {nextState:'RUNNING',cancelStatus:200},
 {nextState:'COMPLETED',cancelStatus:200},
 {nextState:'RUNNING',cancelStatus:500},
])('ignores late cancellation of A after B becomes $nextState (HTTP $cancelStatus)',async({nextState,cancelStatus})=>{
 let finishCancel!:(value:Response)=>void;let firstStream!:ReadableStreamDefaultController<Uint8Array>;let secondSignal:AbortSignal|undefined;let posts=0;
 vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit={})=>{
  if(path.endsWith('/documents'))return response(docs);
  if(path.startsWith('/api/conversations/'))return response({conversationId:'conv1',projectId:'p1',runs:[]});
  if(path==='/api/runs')return response(++posts===1?snapshot:{...snapshot,runId:'r2',prompt:'新问题',state:nextState,lastSeq:1,text:nextState==='COMPLETED'?'第二轮已完成':''});
  if(path==='/api/runs/r1/cancel')return new Promise<Response>(r=>finishCancel=r);
  if(path==='/api/runs/r1/events')return new Response(new ReadableStream({start(c){firstStream=c;c.enqueue(new TextEncoder().encode('data: '+JSON.stringify(ev(1,'started',{kind:'ANSWER'}))+'\n\n'));}}),{headers:{'Content-Type':'text/event-stream'}});
  if(path==='/api/runs/r2/events'){secondSignal=options.signal as AbortSignal;return new Response(new ReadableStream({start(){}}),{headers:{'Content-Type':'text/event-stream'}});}
  throw Error(path);
 }));
 await start();fireEvent.click(await screen.findByRole('button',{name:'停止生成'}));
 await act(async()=>{firstStream.enqueue(new TextEncoder().encode('data: '+JSON.stringify(ev(2,'completed',{state:'COMPLETED'}))+'\n\n'));});
 fireEvent.change(screen.getByLabelText('研究问题'),{target:{value:'新问题'}});fireEvent.click(screen.getByRole('button',{name:'开始研究'}));
 await waitFor(()=>expect(new URL(window.location.href).searchParams.get('run')).toBe('r2'));
 await act(async()=>finishCancel(cancelStatus===200?response({...snapshot,state:'CANCELLED',lastSeq:3}):response({code:'FAILED',message:'旧取消失败',requestId:'q1'},500)));
 expect(document.querySelector('.research-current-prompt')?.textContent).toBe('新问题');expect(screen.queryByRole('alert')).toBeNull();
 if(nextState==='RUNNING')expect(secondSignal?.aborted).toBe(false);
});
