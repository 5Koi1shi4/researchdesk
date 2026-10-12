package com.researchdesk.research;
import com.researchdesk.identity.*;import jakarta.servlet.http.HttpServletRequest;import java.util.*;
import org.springframework.http.ResponseEntity;import org.springframework.web.bind.annotation.*;import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
@RestController
public class RunStreamController {
 private final GenerationService runs;private final SessionService sessions;
 public RunStreamController(GenerationService runs,SessionService sessions){this.runs=runs;this.sessions=sessions;}
 @GetMapping(value="/api/runs/{id}/events",produces="text/event-stream") public ResponseEntity<SseEmitter> events(@PathVariable UUID id,@RequestHeader(value="Last-Event-ID",defaultValue="0") String last,HttpServletRequest request){
  UUID owner=sessions.require(request).user().id();long after;try{after=Long.parseLong(last);}catch(Exception e){throw new ApiFailure(400,"INVALID_EVENT_CURSOR","无效的事件序号。");}
  runs.events(owner,id,after); // authorization/cursor errors must be normal JSON before headers commit
  var emitter=new SseEmitter(330000L);var stopped=new java.util.concurrent.atomic.AtomicBoolean();emitter.onCompletion(()->stopped.set(true));emitter.onTimeout(()->stopped.set(true));emitter.onError(e->stopped.set(true));final long initial=after;
  Thread.startVirtualThread(()->{long seq=initial,lastBeat=0;try{while(!stopped.get()){
   for(var event:runs.events(owner,id,seq)){emitter.send(SseEmitter.event().id(Long.toString(event.seq())).name(event.type().name().toLowerCase(Locale.ROOT)).data(event));seq=event.seq();}
   var snapshot=runs.get(owner,id);if(GenerationService.terminal(snapshot.state())&&seq>=snapshot.lastSeq()){emitter.complete();return;}
   if(System.currentTimeMillis()-lastBeat>10000){emitter.send(SseEmitter.event().comment("heartbeat"));lastBeat=System.currentTimeMillis();}Thread.sleep(200);
  }}catch(Exception e){emitter.completeWithError(e);}});
  return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Accel-Buffering","no").body(emitter);
 }
}
