package com.researchdesk.research;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchdesk.Contracts.*;
import com.researchdesk.identity.ApiFailure;
import com.researchdesk.project.ProjectService;
import java.sql.*; import java.time.*; import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GenerationService {
 public record Snapshot(String runId,String conversationId,String projectId,RunKind kind,List<String> documentVersionIds,String prompt,RunState state,String text,List<Citation> citations,long lastSeq,String mode,ApiError error) {}
 public record Conversation(String conversationId,String projectId,Instant createdAt) {}
 public record ConversationDetail(String conversationId,String projectId,List<Snapshot> runs) {}
 private final JdbcTemplate db; private final TransactionTemplate tx; private final ObjectMapper json; private final ProjectService projects; private final String mode; private final List<ReportDraftSink> sinks;
 public GenerationService(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,ProjectService projects,@Value("${GENERATION_MODE:demo}") String mode,List<ReportDraftSink> sinks){this.db=db;this.tx=tx;this.json=json;this.projects=projects;this.mode=mode.equals("demo")?"DEMO":"REAL";this.sinks=sinks;}
 public static List<Map<String,String>> boundedHistory(List<Map<String,String>> all){
  var result=new LinkedList<Map<String,String>>(); int remaining=8000;
  // UTF-8 bytes conservatively upper-bound byte-based model tokens, including emoji and rare scripts.
  for(int i=all.size()-1;i>=Math.max(0,all.size()-12);i--){var m=all.get(i);int cost=m.get("content").getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(cost>remaining)break;result.addFirst(Map.copyOf(m));remaining-=cost;}return List.copyOf(result);
 }
 String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode run",e);}}
 <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("Cannot read run",e);}}
 List<String> strings(String value){try{return json.readValue(value,new TypeReference<List<String>>(){});}catch(Exception e){throw new IllegalStateException(e);}}
 List<Map<String,String>> history(String value){try{return json.readValue(value,new TypeReference<List<Map<String,String>>>(){});}catch(Exception e){throw new IllegalStateException(e);}}
 List<Citation> citations(String value){try{return json.readValue(value,new TypeReference<List<Citation>>(){});}catch(Exception e){throw new IllegalStateException(e);}}
 private Snapshot map(ResultSet r,int n)throws SQLException{return new Snapshot(r.getString("id"),r.getString("conversation_id"),r.getString("project_id"),RunKind.valueOf(r.getString("kind")),strings(r.getString("document_version_ids")),r.getString("prompt"),RunState.valueOf(r.getString("state")),r.getString("text"),citations(r.getString("citations")),r.getLong("last_seq"),r.getString("mode"),r.getString("error")==null?null:decode(r.getString("error"),ApiError.class));}
 private static UUID id(String value){try{return UUID.fromString(value);}catch(Exception e){throw new ApiFailure(400,"INVALID_REQUEST","无效的资源标识。");}}
 static boolean terminal(RunState state){return state==RunState.COMPLETED||state==RunState.CANCELLED||state==RunState.FAILED;}
 public Snapshot create(UUID owner,GenerationRequest input){
  if(input==null||input.kind()==null||input.prompt()==null||input.prompt().isBlank()||input.prompt().length()>16000||input.documentVersionIds()==null||input.documentVersionIds().size()>50)throw new ApiFailure(400,"INVALID_REQUEST","请输入问题并选择最多 50 份资料。");
  UUID project=id(input.projectId());var scope=input.documentVersionIds().stream().map(GenerationService::id).distinct().sorted().map(UUID::toString).toList();
  return tx.execute(status->{
   projects.findByIdAndOwnerId(project,owner);
   db.queryForObject("SELECT id FROM business.users WHERE id=? FOR UPDATE",UUID.class,owner);
   UUID conversation=input.conversationId()==null?UUID.randomUUID():id(input.conversationId());
   if(input.conversationId()!=null)requireConversation(owner,conversation,project);
   if(db.queryForObject("SELECT count(*) FROM business.generation_runs WHERE owner_id=? AND state IN ('QUEUED','RUNNING')",Integer.class,owner)>=2)throw new ApiFailure(429,"RUN_CONCURRENCY_LIMIT","最多同时运行两个生成任务，请等待或停止已有任务。");
   requireScope(project,scope,true);
   if(input.conversationId()==null)db.update("INSERT INTO business.conversations(id,project_id) VALUES (?,?)",conversation,project);
   var messages=new ArrayList<Map<String,String>>();
   var previous=db.query("SELECT * FROM business.generation_runs WHERE conversation_id=? AND state='COMPLETED' ORDER BY created_at DESC,id DESC LIMIT 6",this::map,conversation);Collections.reverse(previous);
   for(var run:previous){messages.add(Map.of("role","user","content",run.prompt()));messages.add(Map.of("role","assistant","content",run.text()));}
   UUID runId=UUID.randomUUID();db.update("INSERT INTO business.generation_runs(id,project_id,owner_id,conversation_id,kind,state,prompt,document_version_ids,history,mode) VALUES (?,?,?,?,?,'QUEUED',?,?::jsonb,?::jsonb,?)",runId,project,owner,conversation,input.kind().name(),input.prompt(),encode(scope),encode(boundedHistory(messages)),mode);
   return raw(runId,false);
  });
 }
 private void requireConversation(UUID owner,UUID conversation,UUID project){if(db.queryForObject("SELECT count(*) FROM business.conversations c JOIN business.projects p ON p.id=c.project_id WHERE c.id=? AND c.project_id=? AND p.owner_id=?",Integer.class,conversation,project,owner)!=1)throw missing();}
 private static ApiFailure missing(){return new ApiFailure(404,"RUN_NOT_FOUND","研究记录不存在。");}
 Snapshot raw(UUID run,boolean lock){return db.query("SELECT * FROM business.generation_runs WHERE id=?"+(lock?" FOR UPDATE":""),this::map,run).stream().findFirst().orElseThrow(GenerationService::missing);}
 public Snapshot get(UUID owner,UUID run){var s=db.query("SELECT r.* FROM business.generation_runs r JOIN business.projects p ON p.id=r.project_id WHERE r.id=? AND p.owner_id=?",this::map,run,owner).stream().findFirst().orElseThrow(GenerationService::missing);return redact(s);}
 Snapshot redact(Snapshot s){var cs=s.citations().stream().map(c->available(id(s.projectId()),c.documentVersionId())?c:new Citation(c.chunkId(),c.documentVersionId(),c.title(),c.page(),c.paragraph(),null,Availability.DELETED)).toList();return new Snapshot(s.runId(),s.conversationId(),s.projectId(),s.kind(),s.documentVersionIds(),s.prompt(),s.state(),s.text(),cs,s.lastSeq(),s.mode(),s.error());}
 boolean available(UUID project,String version){return db.queryForObject("SELECT count(*) FROM business.document_versions v JOIN business.documents d ON d.id=v.document_id WHERE v.id=? AND v.project_id=? AND d.state='READY'",Integer.class,id(version),project)==1;}
 void requireScope(UUID project,List<String> scope,boolean lock){for(String version:scope){var rows=db.queryForList("SELECT d.state FROM business.document_versions v JOIN business.documents d ON d.id=v.document_id WHERE v.id=? AND v.project_id=?"+(lock?" FOR SHARE OF d,v":""),id(version),project);if(rows.isEmpty())throw new ApiFailure(404,"SOURCE_NOT_FOUND","资料不存在。");if(!"READY".equals(rows.getFirst().get("state")))throw new ApiFailure(409,"SOURCE_UNAVAILABLE","选定资料不可用，请重新选择资料后重试。");}}
 public List<Conversation> conversations(UUID owner,UUID project){projects.findByIdAndOwnerId(project,owner);return db.query("SELECT id,project_id,created_at FROM business.conversations WHERE project_id=? ORDER BY created_at DESC,id",(r,n)->new Conversation(r.getString(1),r.getString(2),r.getTimestamp(3).toInstant()),project);}
 public ConversationDetail conversation(UUID owner,UUID conversation){var project=db.query("SELECT c.project_id FROM business.conversations c JOIN business.projects p ON p.id=c.project_id WHERE c.id=? AND p.owner_id=?",(r,n)->r.getObject(1,UUID.class),conversation,owner).stream().findFirst().orElseThrow(GenerationService::missing);return new ConversationDetail(conversation.toString(),project.toString(),db.query("SELECT * FROM business.generation_runs WHERE conversation_id=? ORDER BY created_at,id",this::map,conversation).stream().map(this::redact).toList());}
 public Snapshot cancel(UUID owner,UUID run){get(owner,run);append(run,EventType.CANCELLED,Map.of("state","CANCELLED"));return get(owner,run);}
 /** Atomically assigns authoritative sequence, folds snapshot and appends durable event. */
 public boolean append(UUID run,EventType type,Map<String,Object> payload){RunEventValidator.validate(type,payload);return Boolean.TRUE.equals(tx.execute(status->{
  var s=raw(run,true);if(terminal(s.state()))return false;
  if(type==EventType.STARTED){if(s.state()!=RunState.QUEUED)return false;if(!s.kind().name().equals(payload.get("kind")))throw new IllegalArgumentException("Invalid start");}
  else if(s.state()!=RunState.RUNNING&&type!=EventType.CANCELLED&&type!=EventType.FAILED)throw new IllegalArgumentException("Run not started");
  RunState next=s.state();String text=s.text();var cs=new ArrayList<>(s.citations());ApiError error=s.error();
  switch(type){
   case STARTED->next=RunState.RUNNING;
   case DELTA->{text+=payload.get("text");if(text.length()>120000)throw new IllegalArgumentException("Output too large");}
   case CITATION->{Citation citation=json.convertValue(payload.get("citation"),Citation.class);if(!s.documentVersionIds().contains(citation.documentVersionId()))throw new IllegalArgumentException("Citation outside scope");cs.removeIf(c->c.chunkId().equals(citation.chunkId()));cs.add(citation);if(cs.size()>100)throw new IllegalArgumentException("Too many citations");}
   case COMPLETED->{requireScope(id(s.projectId()),s.documentVersionIds(),true);next=RunState.COMPLETED;if(s.kind()==RunKind.REPORT)for(var sink:sinks)sink.saveDraft(s.runId(),s.projectId(),text,List.copyOf(cs));}
   case FAILED->{next=RunState.FAILED;error=json.convertValue(payload.get("error"),ApiError.class);}
   case CANCELLED->next=RunState.CANCELLED;
  }
  long seq=s.lastSeq()+1;db.update("UPDATE business.generation_runs SET state=?,text=?,citations=?::jsonb,error=?::jsonb,last_seq=?,updated_at=now(),deadline=CASE WHEN ?='RUNNING' AND deadline IS NULL THEN now()+(? * interval '1 second') ELSE deadline END WHERE id=?",next.name(),text,encode(cs),error==null?null:encode(error),seq,next.name(),s.kind()==RunKind.ANSWER?120:300,run);
  db.update("INSERT INTO business.run_events(run_id,seq,type,payload) VALUES (?,?,?,?::jsonb)",run,seq,type.name().toLowerCase(Locale.ROOT),encode(payload));return true;
 }));}
 public void fail(UUID run,String code,String message){append(run,EventType.FAILED,Map.of("error",Map.of("code",code,"message",message,"requestId",UUID.randomUUID().toString())));}
 Map<String,Object> providerRequest(UUID run){var s=raw(run,false);var titles=new HashMap<String,String>();for(var version:s.documentVersionIds())db.query("SELECT d.title FROM business.documents d JOIN business.document_versions v ON v.document_id=d.id WHERE v.id=?",r->{titles.put(version,r.getString(1));},id(version));return Map.of("runId",s.runId(),"kind",s.kind().name(),"projectId",s.projectId(),"documentVersionIds",s.documentVersionIds(),"prompt",s.prompt(),"history",history(db.queryForObject("SELECT history::text FROM business.generation_runs WHERE id=?",String.class,run)),"titles",titles);}
 List<UUID> queued(){return db.query("SELECT id FROM business.generation_runs WHERE state='QUEUED' ORDER BY created_at LIMIT 8",(r,n)->r.getObject(1,UUID.class));}
 void expire(){for(var run:db.query("SELECT id FROM business.generation_runs WHERE state='RUNNING' AND deadline<now()",(r,n)->r.getObject(1,UUID.class)))fail(run,"RUN_DEADLINE_EXCEEDED","生成已超时，请显式发起新的任务。");db.update("DELETE FROM business.run_events WHERE created_at<now()-interval '24 hours'");}
 public List<RunEvent> events(UUID owner,UUID run,long after){var s=get(owner,run);if(after<0||after>s.lastSeq())throw new ApiFailure(400,"INVALID_EVENT_CURSOR","无效的事件序号。");Long earliest=db.queryForObject("SELECT min(seq) FROM business.run_events WHERE run_id=? AND created_at>=now()-interval '24 hours'",Long.class,run);if(after<s.lastSeq()&&(earliest==null||after<earliest-1))throw new ApiFailure(410,"EVENT_WINDOW_EXPIRED","事件已过期，请读取完整运行快照。");return db.query("SELECT seq,type,payload::text FROM business.run_events WHERE run_id=? AND seq>? AND created_at>=now()-interval '24 hours' ORDER BY seq LIMIT 256",(r,n)->{
   @SuppressWarnings("unchecked") Map<String,Object> p=decode(r.getString(3),Map.class);EventType t=EventType.valueOf(r.getString(2).toUpperCase(Locale.ROOT));
   if(t==EventType.CITATION){Citation c=json.convertValue(p.get("citation"),Citation.class);if(!available(id(s.projectId()),c.documentVersionId()))p=Map.of("citation",json.convertValue(new Citation(c.chunkId(),c.documentVersionId(),c.title(),c.page(),c.paragraph(),null,Availability.DELETED),Map.class));}
   return new RunEvent(run.toString(),r.getLong(1),t,p);
  },run,after);}
}
