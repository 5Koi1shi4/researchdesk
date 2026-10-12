import {useCallback,useEffect,useRef,useState,type FormEvent} from 'react';
import {api,currentAuthGeneration,type Project,type Session} from '../../api';
import {SourceViewer} from './SourceViewer';
import type {components} from '../../generated/contracts';
type Document=components['schemas']['Document'];
const labels:Record<Document['state'],string>={PROCESSING:'处理中',READY:'可用于研究',FAILED:'处理失败',DELETING:'正在删除',DELETED:'已删除'};
export function DocumentPage({project,session}:{project:Project;session:Session}) {
 const [documents,setDocuments]=useState<Document[]>([]),[file,setFile]=useState<File|null>(null),[loading,setLoading]=useState(true),[busy,setBusy]=useState(false),[listError,setListError]=useState(''),[uploadError,setUploadError]=useState(''),[notice,setNotice]=useState('');
 const [preview,setPreview]=useState<Document|null>(null),[deleting,setDeleting]=useState<Document|null>(null);
 const previewButton=useRef<HTMLButtonElement|null>(null),deleteButton=useRef<HTMLButtonElement|null>(null),confirmButton=useRef<HTMLButtonElement>(null),closePreviewButton=useRef<HTMLButtonElement>(null);
 useEffect(()=>{if(deleting)confirmButton.current?.focus();},[deleting]);
 useEffect(()=>{if(preview)closePreviewButton.current?.focus();},[preview]);
 const scope=useRef(0),latestRead=useRef(0),input=useRef<HTMLInputElement>(null);
 const refresh=useCallback(async()=>{
  const epoch=scope.current,generation=currentAuthGeneration(),request=++latestRead.current;
  const current=()=>epoch===scope.current&&generation===currentAuthGeneration()&&request===latestRead.current;
  try {const result=await api<Document[]>(`/api/projects/${project.id}/documents`);if(current()){setDocuments(result);setPreview(p=>p&&result.some(d=>d.documentId===p.documentId&&d.state==='READY')?p:null);setListError('');}}
  catch(e){if(current())setListError(e instanceof Error?e.message:'无法加载资料，请检查连接后刷新。');}
  finally{if(current())setLoading(false);}
 },[project.id]);
 useEffect(()=>{scope.current++;setPreview(null);setDeleting(null);setDocuments([]);setFile(null);setLoading(true);setBusy(false);setListError('');setUploadError('');setNotice('');void refresh();return()=>{scope.current++;};},[refresh,session.user.id]);
 useEffect(()=>{if(!documents.some(d=>d.state==='PROCESSING'||d.state==='DELETING'))return;const timer=window.setInterval(()=>void refresh(),3000);return()=>clearInterval(timer);},[documents,refresh]);
 async function upload(e:FormEvent) {
  e.preventDefault();if(!file)return;const epoch=scope.current,generation=currentAuthGeneration();const current=()=>epoch===scope.current&&generation===currentAuthGeneration();
  setBusy(true);setUploadError('');setNotice('');
  try {
   const body=new FormData();body.append('file',file);
   await api<components['schemas']['DocumentUpload']>(`/api/projects/${project.id}/documents`,'POST',body,session.csrfToken);
   if(current()){setFile(null);if(input.current)input.current.value='';setNotice('资料已提交。处理状态以资料列表为准。');await refresh();}
  }catch(e){if(current())setUploadError(e instanceof Error?e.message:'上传失败，请检查连接后重试。');}
  finally{if(current())setBusy(false);}
 }
 async function retry(document:Document) {
  const epoch=scope.current,generation=currentAuthGeneration();const current=()=>epoch===scope.current&&generation===currentAuthGeneration();
  setBusy(true);setUploadError('');
  try{await api(`/api/projects/${project.id}/tasks/${document.taskId}/retry`,'POST',{},session.csrfToken);if(current())await refresh();}
  catch(e){if(current())setUploadError(e instanceof Error?e.message:'重试暂时失败，请稍后再试。');}
  finally{if(current())setBusy(false);}
 }
 async function removeDocument(document:Document){
  const epoch=scope.current,generation=currentAuthGeneration();const current=()=>epoch===scope.current&&generation===currentAuthGeneration();
  setBusy(true);setUploadError('');setNotice('');setPreview(null);
  try{await api<components['schemas']['SourceDeletion']>(`/api/documents/${document.documentId}`,'DELETE',undefined,session.csrfToken);if(current()){setDeleting(null);setDocuments(ds=>ds.map(d=>d.documentId===document.documentId?{...d,state:'DELETING'}:d));setNotice('资料已撤销访问。历史回答正文仍会保留。');await refresh();}}
  catch(e){if(current())setUploadError(e instanceof Error?e.message:'删除失败，请稍后再试。');}
  finally{if(current())setBusy(false);}
 }
 return <section className="documents" aria-label="项目资料"><div className="documents-heading"><h2>项目资料</h2><button className="secondary" onClick={()=>void refresh()} disabled={busy}>刷新资料状态</button></div>
 <form className="upload-form" onSubmit={upload}><p id="upload-disclosure">上传的资料将用于你配置的模型服务。请确认你有权上传并使用这些内容。</p><p className="caption" id="upload-limits">支持含文字的 PDF（最多 200 页）和 UTF-8 Markdown。单文件最大 20 MiB，每项目最多 50 份；部署设置可能更严格。不支持扫描件 OCR。</p>
 <label htmlFor="document-file">资料文件</label><input ref={input} id="document-file" type="file" accept=".pdf,.md,.markdown" aria-describedby="upload-disclosure upload-limits" disabled={busy} onChange={e=>{setFile(e.target.files?.[0]??null);setUploadError('');setNotice('');}}/>
 <button type="submit" disabled={busy||!file}>{busy?'正在上传…':'上传资料'}</button></form>
 {uploadError?<p role="alert" className="error">{uploadError}</p>:null}{listError?<p role="alert" className="error">{listError}</p>:null}{notice?<p role="status" className="upload-notice">{notice}</p>:null}
 {loading?<p role="status">正在加载资料…</p>:documents.length===0?<section className="empty-state"><h3>上传第一份资料</h3><p>选择研究所需的原始资料，随后在这里查看处理状态。</p></section>:<ul className="document-list">{documents.map(d=><li key={d.documentId}><div><h3>{d.title}</h3><p className="caption">{(d.sizeBytes/1024).toFixed(1)} KiB{d.pageCount!==null?` · ${d.pageCount} 页`:''} · 上传于 {new Date(d.createdAt).toLocaleDateString('zh-CN')}</p>{d.state==='FAILED'?<><p className="error">{d.errorMessage||'资料处理失败，请检查原文件后重新上传。'}</p>{d.errorCode==='INGESTION_UNAVAILABLE'?<button className="secondary" disabled={busy} onClick={()=>void retry(d)}>重试处理</button>:null}</>:null}{d.state==='PROCESSING'?<p className="caption">资料正在等待或进行处理，完成后才可用于研究。</p>:null}<div className="document-actions">{d.state==='READY'?<button className="secondary" disabled={busy} onClick={e=>{previewButton.current=e.currentTarget;setPreview(d);}}>预览原文</button>:null}{d.state!=='DELETING'&&d.state!=='DELETED'?<button className="secondary danger" disabled={busy} onClick={e=>{deleteButton.current=e.currentTarget;setDeleting(d);}}>删除资料</button>:null}</div></div><span className={`document-state state-${d.state.toLowerCase()}`}>{labels[d.state]}</span></li>)}</ul>}
 {deleting?<section className="delete-confirm" aria-label="删除确认" onKeyDown={e=>{if(e.key==='Escape'&&!busy){setDeleting(null);deleteButton.current?.focus();}}}><h3>删除「{deleting.title}」？</h3><p>删除后无法查看或检索原文，旧引用将显示已删除。历史回答正文仍会保留。</p><div className="document-actions"><button ref={confirmButton} className="danger" disabled={busy} onClick={()=>void removeDocument(deleting)}>确认删除</button><button className="secondary" disabled={busy} onClick={()=>{setDeleting(null);deleteButton.current?.focus();}}>取消删除</button></div></section>:null}
 {preview?<div className="source-panel" onKeyDown={e=>{if(e.key==='Escape'){setPreview(null);previewButton.current?.focus();}}}><button ref={closePreviewButton} className="secondary" onClick={()=>{setPreview(null);previewButton.current?.focus();}}>关闭原文</button><SourceViewer citation={{chunkId:'',documentVersionId:preview.versionId,title:preview.title,page:1,paragraph:1,excerpt:null,availability:'AVAILABLE'}}/></div>:null}
 </section>;
}
