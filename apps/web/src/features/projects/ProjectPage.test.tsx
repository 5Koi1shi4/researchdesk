// @vitest-environment jsdom
import {cleanup,render,screen} from '@testing-library/react';
import {afterEach,expect,it,vi} from 'vitest';
import {ProjectPage} from './ProjectPage';
afterEach(()=>{cleanup();vi.unstubAllGlobals();window.history.replaceState({},'', '/');});
it('restores project and run links on refresh',async()=>{
 window.history.replaceState({},'', '/?project=p1&run=r1');
 const project={id:'p1',name:'恢复的项目',createdAt:'2026-10-12T00:00:00Z'};
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>new Response(JSON.stringify(path==='/api/projects'?[project]:path==='/api/projects/p1'?project:(path.endsWith('/documents')||path.endsWith('/usage'))?[]:path.startsWith('/api/conversations/')?{conversationId:'c1',projectId:'p1',runs:[]}:{runId:'r1',projectId:'p1',conversationId:'c1',kind:'ANSWER',documentVersionIds:[],prompt:'问题',mode:'DEMO',state:'COMPLETED',lastSeq:2,text:'恢复的研究答案',citations:[],error:null}),{status:200,headers:{'Content-Type':'application/json'}})));
 render(<ProjectPage session={{user:{id:'u1',email:'u@test.dev'},csrfToken:'csrf'}} onLogout={()=>{}}/>);
 expect(await screen.findByText('恢复的研究答案')).toBeTruthy();
});
it('opens authorized full source from research citations in the project shell',async()=>{
 window.history.replaceState({},'', '/?project=p1&run=r1');
 const project={id:'p1',name:'项目',createdAt:'2026-10-12T00:00:00Z'};
 const citation={chunkId:'c1',documentVersionId:'v1',title:'来源',page:null,paragraph:1,excerpt:'摘录',availability:'AVAILABLE'};
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>{
  if(path==='/api/documents/d1/source')return new Response('完整资料独有内容');
  const value=path==='/api/projects'?[project]:path==='/api/projects/p1'?project:(path.endsWith('/documents')||path.endsWith('/usage'))?[]:path.endsWith('/source-info')?{documentId:'d1',title:'来源',mediaType:'text/markdown',availability:'AVAILABLE'}:path.startsWith('/api/conversations/')?{conversationId:'c1',projectId:'p1',runs:[]}:{runId:'r1',projectId:'p1',conversationId:'c1',kind:'ANSWER',documentVersionIds:['v1'],prompt:'问题',mode:'DEMO',state:'COMPLETED',lastSeq:2,text:'答案',citations:[citation],error:null};
  return new Response(JSON.stringify(value),{headers:{'Content-Type':'application/json'}});
 }));
 render(<ProjectPage session={{user:{id:'u1',email:'u@test.dev'},csrfToken:'csrf'}} onLogout={()=>{}}/>);
 (await screen.findByRole('button',{name:'引用 1：来源'})).click();
 expect(await screen.findByText('完整资料独有内容')).toBeTruthy();
});
