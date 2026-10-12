package com.researchdesk.research;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchdesk.Contracts.*;
import com.researchdesk.identity.ApiFailure;
import jakarta.annotation.PreDestroy;
import java.util.*; import java.util.concurrent.*;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** Durable queue claims are never retried after starting a provider. Expired attempts fail explicitly. */
@Component
public class GenerationWorker {
 private final GenerationService runs; private final ObjectMapper json; private final String url,token;
 private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();private final ConcurrentMap<UUID,Call> calls=new ConcurrentHashMap<>();
 public GenerationWorker(GenerationService runs,ObjectMapper json,@Value("${AI_BASE_URL:http://ai:8000}") String url,@Value("${INTERNAL_SERVICE_TOKEN:}") String token){this.runs=runs;this.json=json;this.url=url;this.token=token;}
 @Scheduled(fixedDelayString="${GENERATION_POLL_MS:500}") public void poll(){runs.expire();for(var id:runs.queued()){var s=runs.raw(id,false);if(runs.append(id,EventType.STARTED,Map.of("kind",s.kind().name())))executor.submit(()->generate(id,s.kind()));}for(var entry:calls.entrySet())if(GenerationService.terminal(runs.raw(entry.getKey(),false).state()))entry.getValue().cancel();}
 private void generate(UUID id,RunKind kind){
  try{
   var client=new OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(kind==RunKind.ANSWER?120:300,TimeUnit.SECONDS).callTimeout(kind==RunKind.ANSWER?120:300,TimeUnit.SECONDS).build();
   var request=new Request.Builder().url(url+"/internal/generation/stream").header("Authorization","Bearer "+token).post(RequestBody.create(runs.encode(runs.providerRequest(id)),MediaType.get("application/json"))).build();var call=client.newCall(request);calls.put(id,call);
   if(GenerationService.terminal(runs.raw(id,false).state())){call.cancel();return;}
   try(var response=call.execute()){
    if(!response.isSuccessful()||response.body()==null)throw new IllegalStateException("AI unavailable");
    long expected=1;boolean ended=false;
    var source=response.body().source();
    for(String line;(line=source.readUtf8LineStrict(262144))!=null;){
     var event=json.readValue(line,RunEvent.class);if(!id.toString().equals(event.runId())||event.seq()!=expected++)throw new IllegalArgumentException("Invalid provider sequence");RunEventValidator.validate(event.type(),event.payload());
     if(event.type()==EventType.STARTED){if(expected!=2||!kind.name().equals(event.payload().get("kind")))throw new IllegalArgumentException("Invalid provider start");continue;}
     if(expected==2)throw new IllegalArgumentException("Missing provider start");
     if(!runs.append(id,event.type(),event.payload())){call.cancel();return;}
     if(event.type()==EventType.COMPLETED||event.type()==EventType.FAILED||event.type()==EventType.CANCELLED){ended=true;break;}
    }
    if(!ended)runs.fail(id,"GENERATION_INTERRUPTED","生成连接中断，请显式发起新的任务。");
   }
  }catch(ApiFailure e){runs.fail(id,e.code,e.getMessage());}catch(IllegalArgumentException e){runs.fail(id,"INVALID_GENERATION_EVENT","生成结果验证失败，请重新发起任务。");}catch(Exception e){runs.fail(id,"GENERATION_UNAVAILABLE","生成服务暂时不可用或连接中断，请重新发起任务。");}finally{calls.remove(id);}
 }
 public void cancel(UUID id){var call=calls.get(id);if(call!=null)call.cancel();}
 @PreDestroy public void close(){calls.values().forEach(Call::cancel);executor.shutdownNow();}
}
