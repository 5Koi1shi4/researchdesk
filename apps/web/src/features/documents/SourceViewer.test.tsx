// @vitest-environment jsdom
import {act,cleanup,fireEvent,render,screen} from '@testing-library/react';
import {afterEach,expect,it,vi} from 'vitest';
import {SourceViewer} from './SourceViewer';
import {invalidateAuthRequests} from '../../api';
const citation={chunkId:'chunk-1',documentVersionId:'v-1',title:'研究.md',page:null,paragraph:2,excerpt:'Evidence',availability:'AVAILABLE' as const};
const info={documentId:'doc-1',documentVersionId:'v-1',title:'研究.md',mediaType:'text/markdown',pageCount:null,availability:'AVAILABLE'};
const json=(body:unknown)=>new Response(JSON.stringify(body),{headers:{'Content-Type':'application/json'}});
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
it('renders Markdown as inert text and moves keyboard focus to the cited paragraph',async()=>{
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.endsWith('/source-info')?json(info):new Response('# Heading\n\n<script>alert(1)</script> Evidence\n\nFinal')));
 render(<SourceViewer citation={citation}/>);
 const target=await screen.findByText('<script>alert(1)</script> Evidence');
 expect(document.querySelector('script')).toBeNull();
 fireEvent.click(screen.getByRole('button',{name:'定位引用段落'}));
 expect(document.activeElement).toBe(target);expect(target.getAttribute('aria-current')).toBe('location');
});
it('displays a server rasterized PDF page and bounds keyboard page navigation',async()=>{
 vi.stubGlobal('fetch',vi.fn(async()=>json({...info,title:'研究.pdf',mediaType:'application/pdf',pageCount:3})));
 render(<SourceViewer citation={{...citation,page:2,paragraph:null}}/>);
 const frame=await screen.findByRole('img',{name:'PDF 原文第 2 页'});
 expect(frame.getAttribute('src')).toBe('/api/documents/doc-1/source/pages/2');
 fireEvent.click(screen.getByRole('button',{name:'下一页'}));
 expect(screen.getByRole('img',{name:'PDF 原文第 3 页'}).getAttribute('src')).toBe('/api/documents/doc-1/source/pages/3');
 expect((screen.getByRole('button',{name:'下一页'}) as HTMLButtonElement).disabled).toBe(true);
 fireEvent.click(screen.getByRole('button',{name:'定位引用页'}));
 expect(screen.getByText('第 2 页 / 共 3 页')).toBeTruthy();
});
it('never exposes a deleted citation excerpt or fetches its bytes',()=>{
 const fetch=vi.fn();vi.stubGlobal('fetch',fetch);
 render(<SourceViewer citation={{...citation,availability:'DELETED',excerpt:'private old text'}}/>);
 expect(screen.getByText(/原资料已删除/)).toBeTruthy();expect(screen.queryByText('private old text')).toBeNull();expect(fetch).not.toHaveBeenCalled();
});
it('rechecks source availability and hides old excerpt after server revocation',async()=>{
 vi.stubGlobal('fetch',vi.fn(async()=>json({...info,availability:'DELETED'})));
 render(<SourceViewer citation={citation}/>);
 await screen.findByText(/原资料已删除/);expect(screen.queryByText('Evidence')).toBeNull();
});
it('discards source bytes resolved after authentication changes',async()=>{
 let resolve:(value:Response)=>void=()=>{};
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.endsWith('/source-info')?json(info):new Promise<Response>(r=>resolve=r)));
 render(<SourceViewer citation={citation}/>);
 await screen.findByText('正在加载原文…');
 await act(async()=>{await Promise.resolve();invalidateAuthRequests();resolve(new Response('private delayed source'));});
 expect(screen.queryByText('private delayed source')).toBeNull();
});

it('resets PDF navigation when a different citation in the same document is selected',async()=>{
 vi.stubGlobal('fetch',vi.fn(async()=>json({...info,mediaType:'application/pdf',pageCount:3})));
 const view=render(<SourceViewer citation={{...citation,page:1,paragraph:null}}/>);
 await screen.findByRole('img',{name:'PDF 原文第 1 页'});fireEvent.click(screen.getByRole('button',{name:'下一页'}));
 view.rerender(<SourceViewer citation={{...citation,chunkId:'chunk-3',page:3,paragraph:null}}/>);
 await screen.findByRole('img',{name:'PDF 原文第 3 页'});
});
it('hides an old source during a document switch and rejects late source reads',async()=>{
 let resolve:(value:Response)=>void=()=>{};
 vi.stubGlobal('fetch',vi.fn(async(path:string)=>path.includes('v-2')?new Promise<Response>(r=>resolve=r):path.endsWith('/source-info')?json(info):new Response('private original')));
 const view=render(<SourceViewer citation={citation}/>);await screen.findByText('private original');
 view.rerender(<SourceViewer citation={{...citation,documentVersionId:'v-2',excerpt:'new excerpt'}}/>);
 expect(screen.queryByText('private original')).toBeNull();expect(screen.queryByText('new excerpt')).toBeNull();
 await act(async()=>resolve(json({...info,documentVersionId:'v-2',availability:'DELETED'})));await screen.findByText(/原资料已删除/);
});
