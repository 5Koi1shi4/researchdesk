// @vitest-environment jsdom
import { cleanup,fireEvent,render,screen } from '@testing-library/react';
import {afterEach,expect,it,vi} from 'vitest';
import {ReportPage} from './ReportPage';
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
it('offers a real empty state and creates an empty editable report',async()=>{let created=false;vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{const body=options.method==='POST'?{reportId:'report',version:1,markdown:'',citations:[],createdAt:'2026-10-12',demo:false}:created?path.endsWith('/versions')?[]:path.includes('/versions/')?{reportId:'report',version:1,markdown:'',citations:[],createdAt:'2026-10-12',demo:false}:{id:'report',projectId:'project',title:'研究报告',currentVersion:1,createdAt:'2026-10-12'}:[];if(options.method==='POST')created=true;return new Response(JSON.stringify(body),{headers:{'Content-Type':'application/json'}});}));render(<ReportPage projectId="project" csrfToken="csrf"/>);await screen.findByText('还没有报告');fireEvent.change(screen.getByLabelText('报告标题'),{target:{value:'研究报告'}});fireEvent.click(screen.getByRole('button',{name:'新建空白报告'}));expect(await screen.findByLabelText('报告 Markdown')).toBeTruthy();});
it('opens the exact generated draft and returns to its list without reopening it',async()=>{
 const report={id:'generated',projectId:'project',title:'生成报告',currentVersion:1,createdAt:'2026-10-12'};
 const version={reportId:'generated',version:1,markdown:'生成的正文',citations:[],createdAt:'2026-10-12',demo:true};
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>new Response(JSON.stringify(path==='/api/projects/project/reports'?[report]:path.endsWith('/versions')?[version]:path.includes('/versions/')?version:report),{headers:{'Content-Type':'application/json'}})));
 render(<ReportPage projectId="project" csrfToken="csrf" generatedRunId="run1"/>);
 expect(await screen.findByLabelText('报告 Markdown')).toBeTruthy();
 fireEvent.click(screen.getByRole('button',{name:'返回报告列表'}));
 await screen.findByRole('button',{name:'生成报告 · 版本 1'});
 await new Promise(resolve=>setTimeout(resolve,20));
 expect(screen.queryByLabelText('报告 Markdown')).toBeNull();
});
