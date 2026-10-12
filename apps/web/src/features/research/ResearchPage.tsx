import {useEffect,useRef,useState,type FormEvent,type ReactNode} from 'react';
import {api,currentAuthGeneration,type Project,type Session} from '../../api';
import type {components} from '../../generated/contracts';
import {consumeEventStream,EventProtocolError,initialRun,parseSnapshot,reduceRun,restoreSnapshot,type Citation,type RunSnapshot,type RunView} from '../../lib/events';
type Document=components['schemas']['Document'];
const labels:Record<RunView['status'],string>={QUEUED:'等待生成',RUNNING:'正在生成',COMPLETED:'生成完成',FAILED:'生成失败',CANCELLED:'已停止'};
class SequenceGap extends Error{}
function AnswerText({run,select}:{run:RunView;select:(citation:Citation)=>void}) {
 if(run.status!=='COMPLETED')return <>{run.text}</>;
 return <>{run.text.split(/(\[\[cite:[^\]\r\n]+\]\])/g).map((part,index)=>{
  const match=/^\[\[cite:([^\]]+)\]\]$/.exec(part);
  const citationIndex=match?run.citations.findIndex(c=>c.chunkId===match[1]):-1;
  return citationIndex<0?<span key={index}>{part}</span>:<button key={index} className="inline-citation" aria-label={`查看引用 ${citationIndex+1}`} onClick={()=>select(run.citations[citationIndex])}>[{citationIndex+1}]</button>;
 })}</>;
}
function HistoricalTurn({entry,select}:{entry:RunSnapshot;select:(citation:Citation)=>void}) {
 const view=restoreSnapshot(initialRun(entry.runId),entry);
 return <article className="research-history-turn"><h4>{entry.prompt}</h4><p className="caption">{labels[entry.state]} · {entry.mode==='DEMO'?'演示模式':'模型服务'}{entry.state==='COMPLETED'?' · 引用已验证':' · 未验证草稿'}</p><div className="answer-text"><AnswerText run={view} select={select}/></div>
 {entry.error?<p className="error">{entry.error.message}</p>:null}
 {entry.citations.length?<div className="research-citations" aria-label="历史回答引用">{entry.citations.map((citation,index)=><button className="secondary" key={citation.chunkId} disabled={entry.state!=='COMPLETED'} aria-label={`历史引用 ${index+1}：${citation.title}`} onClick={()=>select(citation)}>[{index+1}] {citation.title}{citation.availability==='DELETED'?' · 已删除':''}</button>)}</div>:null}</article>;
}
const message=(e:unknown)=>e instanceof Error?e.message:'连接失败，请重试。';
function pause(ms:number,signal:AbortSignal){return new Promise<void>(resolve=>{const finish=()=>{clearTimeout(timer);signal.removeEventListener('abort',finish);resolve();};const timer=setTimeout(finish,ms);signal.addEventListener('abort',finish,{once:true});});}
export function ResearchPage({project,session,renderSource,onOpenReports}:{project:Project;session:Session;onOpenReports?:(runId:string)=>void;renderSource?:(citation:Citation)=>ReactNode}) {
 const [documents,setDocuments]=useState<Document[]>([]),[selected,setSelected]=useState<string[]>([]),[prompt,setPrompt]=useState('');
 const [run,setRun]=useState<RunView|null>(null),[snapshot,setSnapshot]=useState<RunSnapshot|null>(null),[source,setSource]=useState<Citation|null>(null);
 const [history,setHistory]=useState<RunSnapshot[]>([]),[historyError,setHistoryError]=useState('');
 const historyRequest=useRef(0),runRequest=useRef(0);
 const [error,setError]=useState(''),[connection,setConnection]=useState(''),[starting,setStarting]=useState(false),[cancelling,setCancelling]=useState(false);
 const currentRun=useRef<RunView|null>(null),scope=useRef(0),controller=useRef<AbortController|null>(null),conversation=useRef<string|undefined>(undefined);
 const publish=(value:RunView|null)=>{currentRun.current=value;setRun(value);};
 const current=(epoch:number,generation:number)=>epoch===scope.current&&generation===currentAuthGeneration();
 // A project/auth scope can contain several runs; only the latest run request may mutate its view.
 const currentRequest=(epoch:number,generation:number,request:number,runId?:string)=>current(epoch,generation)&&request===runRequest.current&&(runId===undefined||currentRun.current?.runId===runId);
 async function follow(initial:RunSnapshot,epoch:number,generation:number,request:number){
  controller.current?.abort();const control=new AbortController();controller.current=control;
  const active=()=>currentRequest(epoch,generation,request,initial.runId)&&!control.signal.aborted;
  let retries=0;
  while(active()&&!currentRun.current?.terminal){
   try{
    setConnection(retries?'正在恢复连接…':'已连接');
    const response=await fetch(`/api/runs/${encodeURIComponent(initial.runId)}/events`,{credentials:'same-origin',cache:'no-store',headers:{Accept:'text/event-stream','Last-Event-ID':String(currentRun.current?.lastSeq??0)},signal:control.signal});
    if(!active())return;
    if(!response.ok){
     if(response.status>=500||response.status===408||response.status===429)throw new Error('事件服务暂时不可用。');
     const failure=await response.json().catch(()=>({message:'无法订阅运行，请恢复快照后重试。'}));
     if(response.status===410&&failure.code==='EVENT_WINDOW_EXPIRED'){
      const restored=parseSnapshot(await api(`/api/runs/${encodeURIComponent(initial.runId)}`));
      if(!active())return;
      if(restored.projectId!==project.id)throw new EventProtocolError('运行不属于当前项目。');
      setSnapshot(restored);publish(restoreSnapshot(currentRun.current!,restored));continue;
     }
     if(response.status===401)window.dispatchEvent(new Event('session-expired'));
     throw new EventProtocolError(failure.message||'无法订阅运行，请重新连接。');
    }
    if(!response.body||!response.headers.get('Content-Type')?.includes('text/event-stream'))throw new EventProtocolError('服务器没有返回事件流。');
    await consumeEventStream(response.body,event=>{
     if(!active())return;
     const next=reduceRun(currentRun.current!,event);publish(next);
     if(next.recovering)throw new SequenceGap();
     if(next.terminal)control.abort();
    },control.signal);
    if(!active()||currentRun.current?.terminal)return;
    throw new Error('连接已中断');
   }catch(failure){
    if(!active())return;
    if(failure instanceof EventProtocolError){setError(message(failure));setConnection('连接已停止');return;}
    if(++retries>3){setConnection('连接中断，可恢复当前运行');return;}
    setConnection('正在恢复连接…');
    if(!(failure instanceof SequenceGap))await pause(500*2**(retries-1),control.signal);
   }
  }
 }
 async function loadHistory(value:RunSnapshot,epoch:number,generation:number,runGeneration:number){
  const request=++historyRequest.current;setHistoryError('');
  try{
   const detail=await api<components['schemas']['RunConversationDetail']>(`/api/conversations/${encodeURIComponent(value.conversationId)}`);
   if(!currentRequest(epoch,generation,runGeneration)||request!==historyRequest.current)return;
   if(!detail||detail.conversationId!==value.conversationId||detail.projectId!==project.id||!Array.isArray(detail.runs))throw new EventProtocolError('会话记录格式无效。');
   const saved=detail.runs.map(parseSnapshot);
   if(saved.some(item=>item.projectId!==project.id||item.conversationId!==value.conversationId))throw new EventProtocolError('会话记录不属于当前项目。');
   setHistory(saved.filter(item=>item.runId!==value.runId).slice(-12));
  }catch(failure){if(currentRequest(epoch,generation,runGeneration)&&request===historyRequest.current)setHistoryError('无法加载会话记录：'+message(failure));}
 }
 async function recover(){
  if(!snapshot||!currentRun.current||currentRun.current.terminal)return;
  const epoch=scope.current,generation=currentAuthGeneration(),request=runRequest.current,runId=currentRun.current.runId;
  controller.current?.abort();setError('');setConnection('正在恢复连接…');
  try{
   const value=parseSnapshot(await api(`/api/runs/${encodeURIComponent(runId)}`));
   if(!currentRequest(epoch,generation,request,runId))return;
   if(value.projectId!==project.id)throw new EventProtocolError('运行不属于当前项目。');
   publish(restoreSnapshot(currentRun.current!,value));setSnapshot(value);
   if(!currentRun.current?.terminal)void follow(value,epoch,generation,request);
  }catch(failure){if(currentRequest(epoch,generation,request,runId)){setError(message(failure));setConnection('连接中断，可恢复当前运行');}}
 }
 function attach(value:RunSnapshot,epoch:number,generation:number,request:number){
  if(!currentRequest(epoch,generation,request))return;
  if(value.projectId!==project.id)throw new Error('运行不属于当前项目。');
  const previous=currentRun.current;
  if(snapshot&&previous&&previous.runId!==value.runId&&snapshot.conversationId===value.conversationId){
   const retained:RunSnapshot={...snapshot,state:previous.status,text:previous.text,citations:previous.citations,lastSeq:previous.lastSeq,error:previous.error};
   setHistory(saved=>[...saved.filter(item=>item.runId!==retained.runId),retained].slice(-12));
  }
  void loadHistory(value,epoch,generation,request);
  setSnapshot(value);conversation.current=value.conversationId;publish(restoreSnapshot(initialRun(value.runId),value));
  const url=new URL(window.location.href);url.searchParams.set('run',value.runId);url.searchParams.set('project',project.id);window.history.replaceState({},'',url);
  if(!['COMPLETED','FAILED','CANCELLED'].includes(value.state))void follow(value,epoch,generation,request);
 }
 useEffect(()=>{
  const epoch=++scope.current,generation=currentAuthGeneration(),request=++runRequest.current;setHistory([]);setHistoryError('');historyRequest.current++;setDocuments([]);setSelected([]);setPrompt('');setSnapshot(null);publish(null);setSource(null);setError('');setConnection('');setStarting(false);setCancelling(false);conversation.current=undefined;
  void api<Document[]>(`/api/projects/${project.id}/documents`).then(value=>{if(current(epoch,generation))setDocuments(value);}).catch(e=>{if(current(epoch,generation))setError(message(e));});
  const id=new URL(window.location.href).searchParams.get('run');
  if(id)void api(`/api/runs/${encodeURIComponent(id)}`).then(value=>{if(currentRequest(epoch,generation,request))attach(parseSnapshot(value),epoch,generation,request);}).catch(e=>{if(currentRequest(epoch,generation,request))setError(message(e));});
  return()=>{scope.current++;controller.current?.abort();};
 },[project.id,session.user.id]);
 async function submit(event:FormEvent,kind:'ANSWER'|'REPORT'='ANSWER'){
  event.preventDefault();if(starting||(run&&!run.terminal)||!prompt.trim()||!selected.length)return;
  const epoch=scope.current,generation=currentAuthGeneration(),request=++runRequest.current;setStarting(true);setError('');setSource(null);setCancelling(false);
  const body:components['schemas']['GenerationRequest']={kind,projectId:project.id,documentVersionIds:selected,prompt:prompt.trim(),...(conversation.current?{conversationId:conversation.current}:{})};
  try{const result=parseSnapshot(await api('/api/runs','POST',body,session.csrfToken));if(currentRequest(epoch,generation,request)){attach(result,epoch,generation,request);setPrompt('');}}
  catch(e){if(currentRequest(epoch,generation,request))setError(message(e));}finally{if(currentRequest(epoch,generation,request))setStarting(false);}
 }
 async function cancel(){
  if(!run||run.terminal||cancelling)return;const epoch=scope.current,generation=currentAuthGeneration(),request=runRequest.current,runId=run.runId;setCancelling(true);setError('');
  try{const value=parseSnapshot(await api(`/api/runs/${encodeURIComponent(run.runId)}/cancel`,'POST',{},session.csrfToken));if(currentRequest(epoch,generation,request,runId)){publish(restoreSnapshot(currentRun.current!,value));setSnapshot(value);if(['COMPLETED','FAILED','CANCELLED'].includes(value.state))controller.current?.abort();}}
  catch(e){if(currentRequest(epoch,generation,request,runId))setError(message(e));}finally{if(currentRequest(epoch,generation,request,runId))setCancelling(false);}
 }
 const active=starting||Boolean(run&&!run.terminal),verified=run?.status==='COMPLETED';
 return <section className="research-workspace" aria-label="研究工作台">
 <header className="research-heading"><div><p className="eyebrow">有据可循</p><h2>研究工作台</h2></div>{snapshot?<span className="mode-badge">{snapshot.mode==='DEMO'?'演示模式 · 非真实模型':'已配置模型服务'}</span>:<p className="caption">基于所选资料回答</p>}</header>
 <p className="caption">生成时，问题、所选资料片段及必要的会话上下文会发送至配置的模型服务。演示运行不调用真实模型。</p><div className="research-columns">
 <aside className="research-sources" aria-label="资料范围"><h3>01 / 资料范围</h3><p className="caption">只使用本次勾选的已就绪资料。</p>{documents.length?documents.map(d=><label className="research-document" key={d.documentId}><input type="checkbox" aria-label={d.title} checked={selected.includes(d.versionId)} disabled={active||d.state!=='READY'} onChange={e=>setSelected(values=>e.target.checked?[...values,d.versionId]:values.filter(id=>id!==d.versionId))}/><span>{d.title}<small>{d.state==='READY'?'可用于研究':'暂不可用'}</small></span></label>):<p>暂无资料，请先上传并等待处理完成。</p>}</aside>
 <div className="research-conversation"><div className="research-answer" tabIndex={0} aria-label="研究回答">
 {history.length?<section aria-label="已保存的会话记录"><p className="caption">最近 {history.length} 轮历史回答</p>{history.map(entry=><HistoricalTurn key={entry.runId} entry={entry} select={setSource}/>)}</section>:null}{historyError?<p className="error" role="alert">{historyError}</p>:null}
 {run?<><h4 className="research-current-prompt">{snapshot?.prompt}</h4><div className="research-status" role="status">{labels[run.status]}{!run.terminal?` · ${connection}`:''}</div><p className="caption">{verified?'引用已验证':'以下为未验证草稿，不应作为已完成答案。'}</p><div className="answer-text">{run.text?<AnswerText run={run} select={setSource}/>:'正在等待回答内容…'}</div>{run.error?<p className="error" role="alert">{run.error.message}</p>:null}
 {run.citations.length?<div className="research-citations" aria-label="回答引用">{run.citations.map((c,index)=><button key={c.chunkId} className="secondary" disabled={!verified} onClick={()=>setSource(c)} aria-label={`引用 ${index+1}：${c.title}`}>[{index+1}] {c.title}{c.availability==='DELETED'?' · 已删除':''}</button>)}</div>:null}</>:<div className="research-empty"><h3>让结论回到资料</h3><p>选择资料并提出问题。回答完成后，可以逐条查看引用证据。</p></div>}</div>
 {verified&&snapshot?.kind==='REPORT'&&onOpenReports?<button onClick={()=>onOpenReports(snapshot.runId)}>打开生成的报告</button>:null}{error?<p role="alert" className="error">{error}</p>:null}
 {run&&!run.terminal&&['连接中断，可恢复当前运行','连接已停止'].includes(connection)?<button className="secondary" onClick={()=>void recover()}>恢复当前运行</button>:null}
 <form onSubmit={event=>void submit(event)} className="research-composer"><label htmlFor="research-prompt">研究问题</label><textarea id="research-prompt" rows={3} maxLength={12000} value={prompt} disabled={active} onChange={e=>setPrompt(e.target.value)} placeholder="例如：这些资料对索引策略有哪些共同结论？"/><div className="composer-actions"><span className="caption">已选 {selected.length} 份资料</span>{run&&!run.terminal?<button type="button" className="secondary" disabled={cancelling} onClick={()=>void cancel()}>{cancelling?'正在请求停止…':'停止生成'}</button>:<><button disabled={active||!selected.length||!prompt.trim()}>{starting?'正在创建运行…':'开始研究'}</button><button type="button" className="secondary" disabled={active||!selected.length||!prompt.trim()} onClick={event=>void submit(event,'REPORT')}>生成报告</button></>}</div>{active?<p className="caption">停止后将保留草稿；供应商仍可能计费。</p>:null}</form></div>
 <aside className="research-evidence" aria-label="引用证据"><h3>03 / 引用证据</h3>{source?<><h4>{source.title}</h4>{source.availability==='DELETED'?<p>此来源已删除，无法查看原文。</p>:<><p className="caption">{source.page?`第 ${source.page} 页`:source.paragraph?`第 ${source.paragraph} 段`:'来源片段'}</p>{renderSource?renderSource(source):<><blockquote>{source.excerpt||'此引用未包含摘录。'}</blockquote><p className="caption">引用快照；完整原文预览将在资料模块接入后提供。</p></>}</>}</>:<p className="caption">完成回答后，选择引用按钮查看证据。支持 Tab 和 Enter 键。</p>}</aside>
 </div></section>;
}
