import type {components} from '../generated/contracts';
export type Citation=components['schemas']['Citation'];
export type RunEvent=components['schemas']['RunEvent'];
export type RunSnapshot=components['schemas']['RunSnapshot'];
export type RunView={runId:string;lastSeq:number;status:components['schemas']['RunState'];text:string;citations:Citation[];terminal:boolean;recovering:boolean;error:components['schemas']['ApiError']|null};
export class EventProtocolError extends Error {}
const fail=():never=>{throw new EventProtocolError('运行数据格式无效，请重新加载运行快照。');};
const object=(v:unknown):v is Record<string,unknown>=>typeof v==='object'&&v!==null&&!Array.isArray(v);
const keys=(v:Record<string,unknown>,expected:string[])=>Object.keys(v).length===expected.length&&expected.every(k=>Object.hasOwn(v,k));
const string=(v:unknown)=>typeof v==='string'&&v.length>0;
const position=(v:unknown)=>v===null||(Number.isSafeInteger(v)&&Number(v)>0);
export function validCitation(v:unknown):v is Citation {
 return object(v)&&keys(v,['chunkId','documentVersionId','title','page','paragraph','excerpt','availability'])&&string(v.chunkId)&&string(v.documentVersionId)&&string(v.title)&&position(v.page)&&position(v.paragraph)&&(v.excerpt===null||typeof v.excerpt==='string')&&(v.availability==='AVAILABLE'||v.availability==='DELETED')&&(v.availability!=='DELETED'||v.excerpt===null);
}
const validError=(v:unknown)=>object(v)&&keys(v,['code','message','requestId'])&&string(v.code)&&string(v.message)&&string(v.requestId);
export function parseRunEvent(value:unknown):RunEvent {
 if(!object(value)||!keys(value,['runId','seq','type','payload'])||!string(value.runId)||!Number.isSafeInteger(value.seq)||Number(value.seq)<1||!object(value.payload))return fail();
 const p=value.payload;
 switch(value.type){
  case 'started':if(!keys(p,['kind'])||!['ANSWER','REPORT'].includes(String(p.kind)))return fail();break;
  case 'delta':if(!keys(p,['text'])||typeof p.text!=='string')return fail();break;
  case 'citation':if(!keys(p,['citation'])||!validCitation(p.citation))return fail();break;
  case 'completed':if(!keys(p,['state'])||p.state!=='COMPLETED')return fail();break;
  case 'cancelled':if(!keys(p,['state'])||p.state!=='CANCELLED')return fail();break;
  case 'failed':if(!keys(p,['error'])||!validError(p.error))return fail();break;
  default:return fail();
 }
 return value as RunEvent;
}
export const initialRun=(runId:string):RunView=>({runId,lastSeq:0,status:'QUEUED',text:'',citations:[],terminal:false,recovering:false,error:null});
export function reduceRun(state:RunView,value:unknown):RunView {
 const event=parseRunEvent(value);
 if(event.runId!==state.runId)return fail();
 if(event.seq<=state.lastSeq||state.terminal)return state;
 if(event.seq!==state.lastSeq+1)return {...state,recovering:true};
 if(event.type==='started'&&state.status!=='QUEUED')return fail();
 if(['delta','citation','completed'].includes(event.type)&&state.status!=='RUNNING')return fail();
 const next={...state,lastSeq:event.seq,recovering:false};
 switch(event.type){
  case 'started':return {...next,status:'RUNNING'};
  case 'delta':return {...next,text:state.text+event.payload.text};
  case 'citation':return {...next,citations:[...state.citations.filter(c=>c.chunkId!==event.payload.citation.chunkId),event.payload.citation]};
  case 'completed':return {...next,status:'COMPLETED',terminal:true};
  case 'failed':return {...next,status:'FAILED',terminal:true,error:event.payload.error};
  case 'cancelled':return {...next,status:'CANCELLED',terminal:true};
 }
}
export function parseSnapshot(value:unknown):RunSnapshot {
 if(!object(value)||!string(value.runId)||!string(value.projectId)||!string(value.conversationId)||!['ANSWER','REPORT'].includes(String(value.kind))||!['QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED'].includes(String(value.state))||!['DEMO','REAL'].includes(String(value.mode))||typeof value.prompt!=='string'||!Array.isArray(value.documentVersionIds)||!value.documentVersionIds.every(string)||typeof value.text!=='string'||!Array.isArray(value.citations)||!value.citations.every(validCitation)||!Number.isSafeInteger(value.lastSeq)||Number(value.lastSeq)<0||(value.error!==null&&!validError(value.error)))return fail();
 if((value.state==='FAILED')!==(value.error!==null))return fail();
 return value as RunSnapshot;
}
export function restoreSnapshot(state:RunView,value:unknown):RunView {
 // An acknowledged terminal event cannot be undone by a racing response.
 if(state.terminal)return state;
 const snapshot=parseSnapshot(value);
 if(snapshot.runId!==state.runId)return fail();
 if(snapshot.lastSeq<state.lastSeq)return state;
 return {...state,lastSeq:snapshot.lastSeq,status:snapshot.state,text:snapshot.text,citations:snapshot.citations,error:snapshot.error,terminal:['COMPLETED','FAILED','CANCELLED'].includes(snapshot.state),recovering:false};
}
export async function consumeEventStream(stream:ReadableStream<Uint8Array>,receive:(event:RunEvent)=>void|Promise<void>,signal?:AbortSignal):Promise<void> {
 const reader=stream.getReader(),decoder=new TextDecoder();let buffer='';
 const abort=()=>{void reader.cancel().catch(()=>{});};signal?.addEventListener('abort',abort,{once:true});if(signal?.aborted)abort();
 try{
  for(;;){
   const {done,value}=await reader.read();buffer+=decoder.decode(value,{stream:!done});
   let match:RegExpExecArray|null;
   while((match=/\r?\n\r?\n/.exec(buffer))!==null){
    const frame=buffer.slice(0,match.index);buffer=buffer.slice(match.index+match[0].length);
    let id:string|undefined,type:string|undefined;const data:string[]=[];
    for(const line of frame.split(/\r?\n/)){
     if(line.startsWith(':'))continue;
     const at=line.indexOf(':'),name=at<0?line:line.slice(0,at),content=at<0?'':line.slice(at+1).replace(/^ /,'');
     if(name==='data')data.push(content);else if(name==='id')id=content;else if(name==='event')type=content;
    }
    if(!data.length)continue;
    let decoded:unknown;try{decoded=JSON.parse(data.join('\n'));}catch{return fail();}
    const event=parseRunEvent(decoded);
    if((id!==undefined&&id!==String(event.seq))||(type!==undefined&&type!==event.type))return fail();
    await receive(event);
   }
   if(buffer.length>1_000_000)return fail();
   if(done)break;
  }
 }finally{signal?.removeEventListener('abort',abort);await reader.cancel().catch(()=>{});reader.releaseLock();}
}
