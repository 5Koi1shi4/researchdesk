import {useEffect,useState} from 'react';
import {api,currentAuthGeneration} from '../../api';
export interface UsageRecord {
 runId:string;kind:string;state:string;mode:'DEMO'|'REAL';provider:string|null;model:string|null;
 inputTokens:number|null;outputTokens:number|null;totalTokens:number|null;
 durationMs:number|null;retrievalMs:number|null;ttftMs:number|null;queueWaitMs:number|null;
 estimatedCost:number|null;currency:string|null;priceDate:string|null;createdAt:string;
}
const value=(n:number|null|undefined)=>n==null?'未知':String(n);
export function UsagePanel({projectId,refreshKey}:{projectId:string;refreshKey?:string|number}){
 const [records,setRecords]=useState<UsageRecord[]>([]);const [error,setError]=useState('');const [loading,setLoading]=useState(true);const [revision,setRevision]=useState(0);
 useEffect(()=>{let active=true;const auth=currentAuthGeneration();setRecords([]);setLoading(true);setError('');
  api<UsageRecord[]>(`/api/projects/${encodeURIComponent(projectId)}/usage`).then(rows=>{if(active&&auth===currentAuthGeneration())setRecords(rows);}).catch(e=>{if(active&&auth===currentAuthGeneration())setError(e instanceof Error?e.message:'用量读取失败。');}).finally(()=>{if(active)setLoading(false);});
  return()=>{active=false;};
 },[projectId,refreshKey,revision]);
 return <section aria-label="模型用量"><h2>模型用量</h2><p>最近 100 次已结束运行。未知表示服务未提供有效数据；成本为估算，不是实际账单。</p><button type="button" onClick={()=>setRevision(n=>n+1)} disabled={loading}>刷新用量</button>
  {error&&<p role="alert">{error}</p>}{loading?<p role="status">正在读取用量…</p>:records.length===0&&!error?<p>暂无已结束运行。</p>:null}
  {records.map(r=><article key={r.runId} aria-label={`运行 ${r.runId}`}><h3>{r.kind==='REPORT'?'报告':'问答'} · {r.state}</h3><p>{r.mode==='DEMO'?'演示 · 不产生真实模型账单':`真实服务 · ${r.provider??'未知供应商'} · ${r.model??'未知模型'}`}</p>
   <dl><dt>输入 tokens</dt><dd>{value(r.inputTokens)}</dd><dt>输出 tokens</dt><dd>{value(r.outputTokens)}</dd><dt>总 tokens</dt><dd>{value(r.totalTokens)}</dd><dt>生成耗时（ms）</dt><dd>{value(r.durationMs)}</dd><dt>检索耗时（ms）</dt><dd>{value(r.retrievalMs)}</dd><dt>首 token 延迟（ms）</dt><dd>{value(r.ttftMs)}</dd><dt>队列等待（ms）</dt><dd>{value(r.queueWaitMs)}</dd></dl>
   <p>成本估算：{r.mode==='REAL'&&r.estimatedCost!=null&&r.currency&&r.priceDate?`${r.estimatedCost} ${r.currency}`:'未知'}</p>{r.mode==='REAL'&&r.estimatedCost!=null&&r.priceDate&&<p>价格配置日期：{r.priceDate}</p>}
  </article>)}
 </section>;
}
