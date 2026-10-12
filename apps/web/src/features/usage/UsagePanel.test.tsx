// @vitest-environment jsdom
import {cleanup,render,screen} from '@testing-library/react';
import {afterEach,it,expect,vi} from 'vitest';
import {UsagePanel} from './UsagePanel';
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
it('shows missing usage as unknown and demo as nonbilling',async()=>{
 vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([{runId:'r',kind:'ANSWER',state:'COMPLETED',mode:'DEMO',model:null,inputTokens:null,outputTokens:null,totalTokens:null,estimatedCost:null,priceDate:null}]),{status:200})));
 render(<UsagePanel projectId="p"/>);await screen.findByText('演示 · 不产生真实模型账单');expect(screen.getAllByText('未知').length).toBeGreaterThan(2);expect(screen.queryByText('0')).toBeNull();
});
it('labels real costs as estimates and displays snapshot date',async()=>{
 vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([{runId:'r',kind:'REPORT',state:'COMPLETED',mode:'REAL',model:'model-x',inputTokens:0,outputTokens:10,totalTokens:10,estimatedCost:0.004,currency:'USD',priceDate:'2026-10-12'}]),{status:200})));
 render(<UsagePanel projectId="p"/>);await screen.findByText(/0.004 USD/);expect(screen.getByText(/价格配置日期：2026-10-12/)).toBeTruthy();expect(screen.getByText('0')).toBeTruthy();
});
