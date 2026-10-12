import {useCallback,useEffect,useRef,useState,type FormEvent} from 'react';
import {api,currentAuthGeneration,type Project,type Session} from '../../api';
import type {components} from '../../generated/contracts';
type Document=components['schemas']['Document'];
const labels:Record<Document['state'],string>={PROCESSING:'处理中',READY:'可用于研究',FAILED:'处理失败',DELETING:'正在删除',DELETED:'已删除'};
export function DocumentPage({project,session}:{project:Project;session:Session}) {
 const [documents,setDocuments]=useState<Document[]>([]),[file,setFile]=useState<File|null>(null),[loading,setLoading]=useState(true),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const scope=useRef(0),latestRead=useRef(0),input=useRef<HTMLInputElement>(null);
 const refresh=useCallback(async()=>{
  const epoch=scope.current,generation=currentAuthGeneration(),request=++latestRead.current;
  const current=()=>epoch===scope.current&&generation===currentAuthGeneration()&&request===latestRead.current;
  try {const result=await api<Document[]>(`/api/projects/${project.id}/documents`);if(current()){setDocuments(result);setError('');}}
  catch(e){if(current())setError(e instanceof Error?e.message:'无法加载资料，请检查连接后刷新。');}
  finally{if(current())setLoading(false);}
 },[project.id]);
 useEffect(()=>{scope.current++;setDocuments([]);setFile(null);setLoading(true);setBusy(false);setError('');setNotice('');void refresh();return()=>{scope.current++;};},[refresh,session.user.id]);
 useEffect(()=>{if(!documents.some(d=>d.state==='PROCESSING'||d.state==='DELETING'))return;const timer=window.setInterval(()=>void refresh(),3000);return()=>clearInterval(timer);},[documents,refresh]);
 async function upload(e:FormEvent) {
  e.preventDefault();if(!file)return;const epoch=scope.current,generation=currentAuthGeneration();const current=()=>epoch===scope.current&&generation===currentAuthGeneration();
  setBusy(true);setError('');setNotice('');
  try {
   const body=new FormData();body.append('file',file);
   await api<components['schemas']['DocumentUpload']>(`/api/projects/${project.id}/documents`,'POST',body,session.csrfToken);
   if(current()){setFile(null);if(input.current)input.current.value='';setNotice('资料已提交。处理状态以资料列表为准。');await refresh();}
  }catch(e){if(current())setError(e instanceof Error?e.message:'上传失败，请检查连接后重试。');}
  finally{if(current())setBusy(false);}
 }
 return <section className="documents" aria-label="项目资料"><div className="documents-heading"><h2>项目资料</h2><button className="secondary" onClick={()=>void refresh()} disabled={busy}>刷新资料状态</button></div>
 <form className="upload-form" onSubmit={upload}><p id="upload-disclosure">上传的资料将用于你配置的模型服务。请确认你有权上传并使用这些内容。</p><p className="caption" id="upload-limits">支持含文字的 PDF（最多 200 页）和 UTF-8 Markdown。单文件最大 20 MiB，每项目最多 50 份；部署设置可能更严格。不支持扫描件 OCR。</p>
 <label htmlFor="document-file">资料文件</label><input ref={input} id="document-file" type="file" accept=".pdf,.md,.markdown" aria-describedby="upload-disclosure upload-limits" disabled={busy} onChange={e=>setFile(e.target.files?.[0]??null)}/>
 <button type="submit" disabled={busy||!file}>{busy?'正在上传…':'上传资料'}</button></form>
 {error?<p role="alert" className="error">{error}</p>:null}{notice?<p role="status" className="upload-notice">{notice}</p>:null}
 {loading?<p role="status">正在加载资料…</p>:documents.length===0?<section className="empty-state"><h3>上传第一份资料</h3><p>选择研究所需的原始资料，随后在这里查看处理状态。</p></section>:<ul className="document-list">{documents.map(d=><li key={d.documentId}><div><h3>{d.title}</h3><p className="caption">{(d.sizeBytes/1024).toFixed(1)} KiB{d.pageCount!==null?` · ${d.pageCount} 页`:''} · 上传于 {new Date(d.createdAt).toLocaleDateString('zh-CN')}</p>{d.state==='FAILED'?<p className="error">{d.errorMessage||'资料处理失败，请检查原文件后重新上传。'}</p>:null}{d.state==='PROCESSING'?<p className="caption">资料正在等待或进行处理，完成后才可用于研究。</p>:null}</div><span className={`document-state state-${d.state.toLowerCase()}`}>{labels[d.state]}</span></li>)}</ul>}
 </section>;
}
