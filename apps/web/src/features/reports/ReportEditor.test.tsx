// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ReportEditor } from './ReportEditor';
import { ReportPreview } from './ReportPreview';
const citation={chunkId:'chunk-a',documentVersionId:'doc-v',title:'中文资料',page:2,paragraph:null,excerpt:'来源摘录',availability:'AVAILABLE' as const};
const report={id:'report-1',projectId:'project-1',title:'报告',currentVersion:1,createdAt:'2026-10-12'};
const version={reportId:'report-1',version:1,markdown:'# 研究\n结论 [[cite:chunk-a]]',citations:[citation],createdAt:'2026-10-12',demo:true};
const response=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});
function setup(post?:(path:string,body:any)=>Response){vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>options.method==='POST'&&post?post(path,JSON.parse(options.body as string)):response(path.endsWith('/versions')?[version]:path.endsWith('/report-1')?report:version)));}
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.restoreAllMocks();});
it('renumbers only present stable citations and keeps raw HTML and remote images inert',()=>{
 const view=render(<ReportPreview markdown={'<script>alert(1)</script>\n![secret](https://remote.test/a.png)\n后者 [[cite:chunk-b]] 前者 [[cite:chunk-a]] 重复 [[cite:chunk-b]]'} citations={[citation,{...citation,chunkId:'chunk-b',title:'第二资料'}]}/>);
 expect(view.container.querySelector('script,img,iframe')).toBeNull();
 expect(screen.getAllByRole('button',{name:'引用 1：第二资料'})).toHaveLength(2);
 view.rerender(<ReportPreview markdown={'只剩 [[cite:chunk-a]]'} citations={[citation,{...citation,chunkId:'chunk-b',title:'第二资料'}]}/>);
 expect(screen.queryByText('第二资料')).toBeNull(); expect(screen.getByRole('button',{name:'引用 1：中文资料'})).toBeTruthy();
});
it('saves explicitly, retains edits on conflict and creates a copy',async()=>{
 setup((path,body)=>path.endsWith('/copies')?response({...version,reportId:'copy-1'}):response({code:'VERSION_CONFLICT',message:'版本已更新'},409));
 const copied=vi.fn();render(<ReportEditor reportId="report-1" csrfToken="csrf" onClose={()=>{}} onReportCopied={copied}/>);
 const editor=await screen.findByLabelText('报告 Markdown'); fireEvent.change(editor,{target:{value:'保留我的草稿'}});
 expect((editor as HTMLTextAreaElement).value).toBe('保留我的草稿');
 fireEvent.click(screen.getByRole('button',{name:'保存新版本'}));
 await screen.findByText(/保留了你的草稿/);expect((editor as HTMLTextAreaElement).value).toBe('保留我的草稿');
 fireEvent.click(screen.getByRole('button',{name:'另存为报告副本'})); await waitFor(()=>expect(copied).toHaveBeenCalledWith('copy-1'));
});
it('guards leaving unsaved changes and shows immutable history',async()=>{
 setup(); const close=vi.fn(); const confirm=vi.spyOn(window,'confirm').mockReturnValue(false);
 render(<ReportEditor reportId="report-1" csrfToken="csrf" onClose={close}/>);
 const editor=await screen.findByLabelText('报告 Markdown');fireEvent.change(editor,{target:{value:'未保存'}});fireEvent.click(screen.getByRole('button',{name:'返回报告列表'}));expect(confirm).toHaveBeenCalled();expect(close).not.toHaveBeenCalled();
 const event=new Event('beforeunload',{cancelable:true});window.dispatchEvent(event);expect(event.defaultPrevented).toBe(true);
 confirm.mockReturnValue(true);fireEvent.change(screen.getByLabelText('查看版本'),{target:{value:'1'}});
 await screen.findByText('历史版本只读');expect(screen.queryByLabelText('报告 Markdown')).toBeNull();
});
it('exports the saved version while preserving unsaved edits',async()=>{
 let requested:any;setup((path,body)=>{requested=body;return response({id:'export-1',reportId:'report-1',version:1,format:'MARKDOWN',state:'SUCCEEDED',errorCode:null,createdAt:'2026-10-12'});});
 render(<ReportEditor reportId="report-1" csrfToken="csrf" onClose={()=>{}}/>);fireEvent.change(await screen.findByLabelText('报告 Markdown'),{target:{value:'未保存正文'}});fireEvent.click(screen.getByRole('button',{name:'导出 Markdown'}));
 await screen.findByRole('link',{name:'下载 Markdown（版本 1）'});expect(requested).toEqual({version:1,format:'MARKDOWN'});expect((screen.getByLabelText('报告 Markdown') as HTMLTextAreaElement).value).toBe('未保存正文');
});

it('keeps stable citation markers as metadata within code',()=>{render(<ReportPreview markdown={'```\n[[cite:chunk-a]]\n```'} citations={[citation]}/>);expect(screen.getByRole('region',{name:'引用附录'})).toBeTruthy();expect(screen.getByRole('button',{name:'引用 1：中文资料'})).toBeTruthy();});
it('renders common Markdown reading structure without activating links',()=>{const view=render(<ReportPreview markdown={'**重点** 和 `代码`\n- 条目\n> 引文\n[链接](javascript:alert(1))'} citations={[]}/>);expect(view.container.querySelector('strong')?.textContent).toBe('重点');expect(view.container.querySelector('code')?.textContent).toBe('代码');expect(view.container.querySelector('li')?.textContent).toBe('条目');expect(view.container.querySelector('blockquote')?.textContent).toBe('引文');expect(view.container.querySelector('a')).toBeNull();});
it('redacts an open citation when its current source becomes deleted',()=>{
 const view=render(<ReportPreview markdown={'结论 [[cite:chunk-a]]'} citations={[citation]}/>);
 fireEvent.click(screen.getByRole('button',{name:'引用 1：中文资料'}));expect(screen.getByText('来源摘录')).toBeTruthy();
 view.rerender(<ReportPreview markdown={'结论 [[cite:chunk-a]]'} citations={[{...citation,availability:'DELETED',excerpt:null}]}/>);
 expect(screen.queryByText('来源摘录')).toBeNull();expect(screen.getByText('来源已删除，原文与摘录无法访问。')).toBeTruthy();
 expect(view.container.querySelector('.report-evidence blockquote')).toBeNull();
});
it('does not expose an excerpt for deleted sources even when supplied',()=>{
 const view=render(<ReportPreview markdown={'结论 [[cite:chunk-a]]'} citations={[{...citation,availability:'DELETED'}]}/>);
 fireEvent.click(screen.getByRole('button',{name:'引用 1：中文资料'}));
 expect(screen.queryByText('来源摘录')).toBeNull();expect(screen.getByText('来源已删除，原文与摘录无法访问。')).toBeTruthy();
 expect(view.container.querySelector('.report-evidence blockquote')).toBeNull();
});
it('uses the current excerpt for an open citation after props update',()=>{
 const view=render(<ReportPreview markdown={'结论 [[cite:chunk-a]]'} citations={[citation]}/>);
 fireEvent.click(screen.getByRole('button',{name:'引用 1：中文资料'}));expect(screen.getByText('来源摘录')).toBeTruthy();
 view.rerender(<ReportPreview markdown={'结论 [[cite:chunk-a]]'} citations={[{...citation,excerpt:'最新来源摘录'}]}/>);
 expect(screen.queryByText('来源摘录')).toBeNull();expect(screen.getByText('最新来源摘录')).toBeTruthy();
});
it('hides the selected excerpt panel when its marker is removed',()=>{
 const view=render(<ReportPreview markdown={'结论 [[cite:chunk-a]]'} citations={[citation]}/>);
 fireEvent.click(screen.getByRole('button',{name:'引用 1：中文资料'}));expect(screen.getByRole('complementary',{name:'引用摘录'})).toBeTruthy();
 view.rerender(<ReportPreview markdown={'结论不再引用资料'} citations={[citation]}/>);
 expect(screen.queryByRole('complementary',{name:'引用摘录'})).toBeNull();expect(screen.queryByText('来源摘录')).toBeNull();
});
it('sends the base version and CSRF and makes a successful save clean',async()=>{let savedBody:any;vi.stubGlobal('fetch',vi.fn(async(path:string,options:RequestInit)=>{if(options.method==='POST'){savedBody=JSON.parse(options.body as string);expect(new Headers(options.headers).get('X-CSRF-Token')).toBe('csrf');return response({...version,version:2,markdown:savedBody.markdown});}return response(path.endsWith('/versions')?[version]:path.endsWith('/report-1')?report:version);}));render(<ReportEditor reportId="report-1" csrfToken="csrf" onClose={()=>{}}/>);fireEvent.change(await screen.findByLabelText('报告 Markdown'),{target:{value:'新版正文'}});fireEvent.click(screen.getByRole('button',{name:'保存新版本'}));await screen.findByText('已保存版本 2');expect(savedBody).toEqual({baseVersion:1,markdown:'新版正文'});const e=new Event('beforeunload',{cancelable:true});window.dispatchEvent(e);expect(e.defaultPrevented).toBe(false);});
