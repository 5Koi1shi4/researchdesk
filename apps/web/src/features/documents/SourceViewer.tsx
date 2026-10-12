import {useEffect,useRef,useState} from 'react';
import {api,ApiFailure,currentAuthGeneration,invalidateAuthRequests} from '../../api';
import type {components} from '../../generated/contracts';
export type Citation=components['schemas']['Citation'];
type SourceInfo=components['schemas']['SourceInfo'];
const deletedMessage='原资料已删除，无法再查看原文。历史回答正文仍会保留。';
export function SourceViewer({citation}:{citation:Citation}) {
 const [source,setSource]=useState<{key:string;info:SourceInfo;text:string}|null>(null);
 const [selectedPage,setSelectedPage]=useState<number|null>(null);
 const [error,setError]=useState(''),[revoked,setRevoked]=useState(false);
 const target=useRef<HTMLParagraphElement>(null);
 const key=citation.documentVersionId;
 useEffect(()=>{
  const controller=new AbortController(),generation=currentAuthGeneration();let active=true;
  const current=()=>active&&generation===currentAuthGeneration();
  setSource(null);setSelectedPage(null);setError('');setRevoked(false);
  if(citation.availability==='DELETED')return;
  async function load(){
   try {
    const info=await api<SourceInfo>(`/api/document-versions/${encodeURIComponent(key)}/source-info`);
    if(!current())return;
    if(info.availability==='DELETED'){setRevoked(true);return;}
    let text='';
    if(info.mediaType==='text/markdown'){
     const response=await fetch(`/api/documents/${encodeURIComponent(info.documentId)}/source`,{credentials:'same-origin',cache:'no-store',signal:controller.signal});
     if(!response.ok){
      if(response.status===401&&current()){invalidateAuthRequests();window.dispatchEvent(new Event('session-expired'));}
      throw new ApiFailure(response.status,'无法加载原文，请刷新后重试。');
     }
     text=await response.text();
    }
    if(current())setSource({key,info,text});
   }catch(e){if(current()){if(e instanceof ApiFailure&&e.status===410)setRevoked(true);else setError(e instanceof Error?e.message:'无法加载原文。');}}
  }
  void load();return()=>{active=false;controller.abort();};
 },[key,citation.availability,citation.chunkId,citation.page]);
 if(citation.availability==='DELETED'||revoked)return <section className="source-viewer" aria-label="原文预览"><p role="status">{deletedMessage}</p></section>;
 if(error)return <section className="source-viewer" aria-label="原文预览"><p role="alert" className="error">{error}</p></section>;
 if(!source||source.key!==key)return <section className="source-viewer" aria-label="原文预览"><p role="status">正在加载原文…</p></section>;
 const paragraphs=source.text.replace(/\r\n/g,'\n').split(/\n\s*\n/).map((text,index)=>({text:text.trim(),number:index+1})).filter(p=>p.text);
 const paragraph=citation.paragraph;
 const found=paragraphs.some(p=>p.number===paragraph);
 const citationPage=Math.max(1,Math.min(Number.isInteger(citation.page)?citation.page!:1,source.info.pageCount||1));
 const page=selectedPage??citationPage;
 return <section className="source-viewer" aria-label="原文预览"><h3>{source.info.title}</h3>
 {citation.excerpt?<blockquote className="citation-excerpt">{citation.excerpt}</blockquote>:null}
 {source.info.mediaType==='application/pdf'?<><p>第 {page} 页{source.info.pageCount?` / 共 ${source.info.pageCount} 页`:''}</p><p className="caption">PDF 按页显示为安全图片，不执行脚本或链接；不提供逐字高亮。</p><div className="document-actions"><button className="secondary" disabled={page<=1} onClick={()=>setSelectedPage(page-1)}>上一页</button><button className="secondary" onClick={()=>setSelectedPage(null)}>定位引用页</button><button className="secondary" disabled={page>=(source.info.pageCount||1)} onClick={()=>setSelectedPage(page+1)}>下一页</button></div><img key={`${key}:${page}`} className="pdf-source-page" alt={`PDF 原文第 ${page} 页`} referrerPolicy="no-referrer" src={`/api/documents/${encodeURIComponent(source.info.documentId)}/source/pages/${page}`} onError={()=>setError('无法加载 PDF 页。资料可能已删除，请关闭后重新打开原文。')}/></>:<>
 {paragraph!==null?<>{found?<button className="secondary" onClick={()=>{target.current?.focus();target.current?.scrollIntoView?.({block:'center'});}}>定位引用段落</button>:<p role="status">引用段落不存在，请核对原文。</p>}</>:null}
 <div className="source-paragraphs">{paragraphs.map(p=><p key={p.number} ref={p.number===paragraph?target:undefined} tabIndex={p.number===paragraph?-1:undefined} aria-current={p.number===paragraph?'location':undefined}><span className="paragraph-number" aria-hidden="true">{p.number}</span>{p.text}</p>)}</div></>}
 </section>;
}
