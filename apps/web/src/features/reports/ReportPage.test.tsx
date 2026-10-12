// @vitest-environment jsdom
import { cleanup,fireEvent,render,screen } from '@testing-library/react';
import {afterEach,expect,it,vi} from 'vitest';
import {ReportPage} from './ReportPage';
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
it('offers a real empty state and creates an empty editable report',async()=>{let created=false;vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{const body=options.method==='POST'?{reportId:'report',version:1,markdown:'',citations:[],createdAt:'2026-10-12',demo:false}:created?path.endsWith('/versions')?[]:path.includes('/versions/')?{reportId:'report',version:1,markdown:'',citations:[],createdAt:'2026-10-12',demo:false}:{id:'report',projectId:'project',title:'研究报告',currentVersion:1,createdAt:'2026-10-12'}:[];if(options.method==='POST')created=true;return new Response(JSON.stringify(body),{headers:{'Content-Type':'application/json'}});}));render(<ReportPage projectId="project" csrfToken="csrf"/>);await screen.findByText('还没有报告');fireEvent.change(screen.getByLabelText('报告标题'),{target:{value:'研究报告'}});fireEvent.click(screen.getByRole('button',{name:'新建空白报告'}));expect(await screen.findByLabelText('报告 Markdown')).toBeTruthy();});

