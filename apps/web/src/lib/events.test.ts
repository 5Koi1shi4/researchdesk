import {describe,it,expect} from 'vitest';
import {initialRun,parseRunEvent,reduceRun,restoreSnapshot,consumeEventStream} from './events';
const event=(seq:number,type:string,payload:unknown)=>({runId:'r1',seq,type,payload});
const started=()=>reduceRun(initialRun('r1'),event(1,'started',{kind:'ANSWER'}));
describe('run event boundary',()=>{
 it.each([
  event(1,'delta',{text:12}),event(0,'started',{kind:'ANSWER'}),event(1,'completed',{state:'CANCELLED'}),
  event(1,'failed',{error:{code:'BAD',message:'bad'}}),event(1,'cancelled',{state:'CANCELLED',text:'extra'}),
  event(1,'citation',{citation:{chunkId:'x'}}),event(1,'unknown',{}),
 ])('rejects malformed variants %#',value=>expect(()=>parseRunEvent(value)).toThrow());
 it('deduplicates replay and refuses gaps until missing events arrive',()=>{
  const one=started(),two=reduceRun(one,event(2,'delta',{text:'A'}));
  expect(reduceRun(two,event(2,'delta',{text:'A'})).text).toBe('A');
  const gap=reduceRun(two,event(4,'delta',{text:'C'}));
  expect(gap.text).toBe('A');expect(gap.lastSeq).toBe(2);expect(gap.recovering).toBe(true);
  const recovered=reduceRun(reduceRun(gap,event(3,'delta',{text:'B'})),event(4,'delta',{text:'C'}));
  expect(recovered.text).toBe('ABC');expect(recovered.recovering).toBe(false);
 });
 it('rejects another run and delta before started',()=>{
  expect(()=>reduceRun(initialRun('r1'),event(1,'delta',{text:'bad'}))).toThrow();
  expect(()=>reduceRun(started(),{...event(2,'delta',{text:'bad'}),runId:'r2'})).toThrow();
 });
 it('keeps cancellation terminal against late success and snapshot',()=>{
  const cancelled=reduceRun(started(),event(2,'cancelled',{state:'CANCELLED'}));
  expect(reduceRun(cancelled,event(3,'completed',{state:'COMPLETED'})).status).toBe('CANCELLED');
  expect(restoreSnapshot(cancelled,{...cancelled,state:'COMPLETED',mode:'DEMO',lastSeq:3,error:null})).toBe(cancelled);
 });
 it('parses split UTF8 SSE frames, ignores heartbeat, checks id against seq',async()=>{
  const bytes=new TextEncoder().encode(': heartbeat\r\n\r\nid: 1\r\nevent: started\r\ndata: '+JSON.stringify(event(1,'started',{kind:'ANSWER'}))+'\r\n\r\nid: 2\ndata: '+JSON.stringify(event(2,'delta',{text:'中文'}))+'\n\n');
  const received:unknown[]=[];
  await consumeEventStream(new ReadableStream({start(c){for(const b of bytes)c.enqueue(Uint8Array.of(b));c.close();}}),e=>{received.push(e);});
  expect(received).toEqual([event(1,'started',{kind:'ANSWER'}),event(2,'delta',{text:'中文'})]);
  await expect(consumeEventStream(new ReadableStream({start(c){c.enqueue(new TextEncoder().encode('id: 2\ndata: '+JSON.stringify(event(1,'started',{kind:'ANSWER'}))+'\n\n'));c.close();}}),()=>{})).rejects.toThrow();
 });
});
