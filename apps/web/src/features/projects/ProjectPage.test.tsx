// @vitest-environment jsdom
import {cleanup,render,screen} from '@testing-library/react';
import {afterEach,expect,it,vi} from 'vitest';
import {ProjectPage} from './ProjectPage';
afterEach(()=>{cleanup();vi.unstubAllGlobals();window.history.replaceState({},'', '/');});
it('restores project and run links on refresh',async()=>{
 window.history.replaceState({},'', '/?project=p1&run=r1');
 const project={id:'p1',name:'恢复的项目',createdAt:'2026-10-12T00:00:00Z'};
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>new Response(JSON.stringify(path==='/api/projects'?[project]:path==='/api/projects/p1'?project:path.endsWith('/documents')?[]:path.startsWith('/api/conversations/')?{conversationId:'c1',projectId:'p1',runs:[]}:{runId:'r1',projectId:'p1',conversationId:'c1',kind:'ANSWER',documentVersionIds:[],prompt:'问题',mode:'DEMO',state:'COMPLETED',lastSeq:2,text:'恢复的研究答案',citations:[],error:null}),{status:200,headers:{'Content-Type':'application/json'}})));
 render(<ProjectPage session={{user:{id:'u1',email:'u@test.dev'},csrfToken:'csrf'}} onLogout={()=>{}}/>);
 expect(await screen.findByText('恢复的研究答案')).toBeTruthy();
});
