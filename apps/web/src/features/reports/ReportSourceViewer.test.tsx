// @vitest-environment jsdom
import {cleanup,fireEvent,render,screen} from '@testing-library/react';
import {afterEach,expect,it} from 'vitest';
import type {Citation} from './reportsApi';
import {ReportPreview} from './ReportPreview';
afterEach(cleanup);
it('passes the currently projected citation to the source renderer and redacts on deletion',()=>{
 const citation={chunkId:'c1',documentVersionId:'v1',title:'来源',page:null,paragraph:1,excerpt:'旧摘录',availability:'AVAILABLE' as const};
 const renderSource=(c:Citation)=><p>{c.availability==='DELETED'?'已撤销':`实时原文 ${c.chunkId}`}</p>;
 const view=render(<ReportPreview markdown="事实[[cite:c1]]" citations={[citation]} renderSource={renderSource}/>);
 fireEvent.click(screen.getByRole('button',{name:'引用 1：来源'}));expect(screen.getByText('实时原文 c1')).toBeTruthy();
 view.rerender(<ReportPreview markdown="事实[[cite:c1]]" citations={[{...citation,availability:'DELETED',excerpt:null}]} renderSource={renderSource}/>);
 expect(screen.queryByText('实时原文 c1')).toBeNull();expect(screen.queryByText('旧摘录')).toBeNull();
});
