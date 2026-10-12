package com.researchdesk.task;

import com.researchdesk.Contracts.InternalTask;
import com.researchdesk.identity.ApiFailure;
import com.researchdesk.project.ProjectService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TaskService {
 private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(TaskService.class);
 public record Attempt(UUID attemptId,long fence) {}
 public record Failure(UUID attemptId,long fence,String code,boolean permanent) {}
 public record Claim(InternalTask task,String mediaType,int leaseSeconds) {}
 private final JdbcTemplate db; private final TransactionTemplate tx; private final ObjectMapper json;private final ProjectService projects;private final int leaseSeconds;
 private static final Set<String> PERMANENT=Set.of("INVALID_UTF8","INVALID_PDF","ENCRYPTED_PDF","PDF_PAGE_LIMIT","DOCUMENT_NO_TEXT","INVALID_DOCUMENT_TYPE","PARSER_RESOURCE_LIMIT","UPLOAD_TOO_LARGE");
 public TaskService(JdbcTemplate db,PlatformTransactionManager manager,ObjectMapper json,ProjectService projects,@Value("${TASK_LEASE_SECONDS:30}") int leaseSeconds){this.db=db;tx=new TransactionTemplate(manager);this.json=json;this.projects=projects;if(leaseSeconds<10||leaseSeconds>120)throw new IllegalArgumentException("Invalid lease limit");this.leaseSeconds=leaseSeconds;}
 private Map<String,Object> locked(UUID id){
  // Match the deletion trigger's document-before-task lock order.
  db.queryForList("SELECT d.id FROM business.documents d JOIN business.document_versions v ON v.document_id=d.id JOIN business.tasks t ON t.document_version_id=v.id WHERE t.id=? FOR UPDATE OF d",id);
  var rows=db.queryForList("SELECT t.*,v.object_key,v.parser_version,v.media_type,d.id document_id,d.state document_state FROM business.tasks t JOIN business.document_versions v ON v.id=t.document_version_id JOIN business.documents d ON d.id=v.document_id WHERE t.id=? FOR UPDATE OF t,d",id);
  if(rows.isEmpty())throw stale();return rows.getFirst();
 }
 private ApiFailure stale(){return new ApiFailure(409,"STALE_ATTEMPT","任务租约已失效。");}
 private boolean same(Map<String,Object> r,Attempt a){return a!=null&&Objects.equals(r.get("attempt_id"),a.attemptId())&&((Number)r.get("fence")).longValue()==a.fence();}
 private void running(Map<String,Object> r,Attempt a){if(!same(r,a)||!"RUNNING".equals(r.get("state"))||!"PROCESSING".equals(r.get("document_state"))||!db.queryForObject("SELECT lease_until>clock_timestamp() FROM business.tasks WHERE id=?",Boolean.class,r.get("id")))throw stale();}
 private InternalTask payload(Map<String,Object> r){return new InternalTask(r.get("id").toString(),r.get("attempt_id").toString(),((Number)r.get("fence")).longValue(),r.get("project_id").toString(),r.get("document_version_id").toString(),r.get("object_key").toString(),r.get("parser_version").toString());}
 public Claim claim(UUID id,Attempt attempt){return tx.execute(s->{
  var r=locked(id);if(!same(r,attempt)||!"QUEUED".equals(r.get("state"))||!"PROCESSING".equals(r.get("document_state"))||!db.queryForObject("SELECT available_at<=clock_timestamp() FROM business.tasks WHERE id=?",Boolean.class,id))throw stale();
  db.update("UPDATE business.tasks SET state='RUNNING',lease_until=clock_timestamp()+(? * interval '1 second') WHERE id=?",leaseSeconds,id);
  db.queryForList("SELECT business.claim_index(document_version_id,project_id,attempt_id,fence,lease_until) FROM business.tasks WHERE id=?",id);
  Long queueWaitMs=db.queryForObject("SELECT GREATEST(0,(EXTRACT(EPOCH FROM (clock_timestamp()-created_at))*1000)::bigint) FROM business.tasks WHERE id=?",Long.class,id);
  LOG.info("ingestion taskId={} attemptId={} queueWaitMs={}",id,attempt.attemptId(),queueWaitMs);
  return new Claim(payload(r),r.get("media_type").toString(),leaseSeconds);
 });}
 public void heartbeat(UUID id,Attempt attempt){tx.executeWithoutResult(s->{var r=locked(id);running(r,attempt);db.update("UPDATE business.tasks SET lease_until=clock_timestamp()+(? * interval '1 second') WHERE id=?",leaseSeconds,id);db.queryForList("SELECT business.renew_index(document_version_id,attempt_id,fence,lease_until) FROM business.tasks WHERE id=?",id);});}
 public void complete(UUID id,Attempt attempt){tx.executeWithoutResult(s->{
  var r=locked(id);
  if(same(r,attempt)&&"SUCCEEDED".equals(r.get("state"))&&"READY".equals(r.get("document_state")))return;
  running(r,attempt);
  int count=db.queryForObject("SELECT business.published_index_count(?,?,?)",Integer.class,r.get("document_version_id"),attempt.attemptId(),attempt.fence());
  if(count<1)throw new ApiFailure(409,"INDEX_NOT_PUBLISHED","资料索引尚未完成。");
  db.update("UPDATE business.tasks SET state='SUCCEEDED',lease_until=NULL,error_code=NULL,error_message=NULL WHERE id=?",id);
  db.update("UPDATE business.documents SET state='READY',error_code=NULL,error_message=NULL WHERE id=?",r.get("document_id"));
 });}
 public void fail(UUID id,Failure failure){tx.executeWithoutResult(s->{var r=locked(id);running(r,new Attempt(failure.attemptId(),failure.fence()));retryOrFail(r,failure.code(),failure.permanent()||PERMANENT.contains(failure.code()));});}
 private void retryOrFail(Map<String,Object> r,String code,boolean permanent){
  db.queryForList("SELECT business.invalidate_index(?)",r.get("document_version_id"));
  int retries=((Number)r.get("retry_count")).intValue();
  if(permanent||retries>=3){db.update("UPDATE business.tasks SET state='FAILED',lease_until=NULL,error_code=?,error_message=? WHERE id=?",safeCode(code),message(code),r.get("id"));db.update("UPDATE business.documents SET state='FAILED',error_code=?,error_message=? WHERE id=?",safeCode(code),message(code),r.get("document_id"));return;}
  enqueue(r,retries+1,(1<<(retries+1))+java.util.concurrent.ThreadLocalRandom.current().nextDouble(0,0.5));
 }
 private String safeCode(String code){return PERMANENT.contains(code)?code:"INGESTION_UNAVAILABLE";}
 private String message(String code){return PERMANENT.contains(code)?"资料无法解析，请检查原文件后上传；格式错误、加密或无文本资料无法自动重试。":"资料处理暂时失败。可重试处理，或稍后再试。";}
 private void enqueue(Map<String,Object> r,int retries,double delay){
  var attempt=UUID.randomUUID();long fence=((Number)r.get("fence")).longValue()+1;
  db.update("UPDATE business.tasks SET state='QUEUED',attempt_id=?,fence=?,retry_count=?,lease_until=NULL,available_at=clock_timestamp()+(? * interval '1 second'),error_code=NULL,error_message=NULL WHERE id=?",attempt,fence,retries,delay,r.get("id"));
  r.put("attempt_id",attempt);r.put("fence",fence);
  try{db.update("INSERT INTO business.outbox(id,task_id,event_type,payload,available_at) VALUES (?,?,'DOCUMENT_PARSE_REQUESTED',?::jsonb,clock_timestamp()+(? * interval '1 second')) ON CONFLICT(task_id) DO UPDATE SET payload=excluded.payload,available_at=excluded.available_at,published_at=NULL",UUID.randomUUID(),r.get("id"),json.writeValueAsString(payload(r)),delay);}catch(Exception e){throw new IllegalStateException(e);}
 }
 public void recover(){
  var ids=db.queryForList("SELECT id FROM business.tasks WHERE state='RUNNING' AND lease_until<=clock_timestamp() ORDER BY lease_until LIMIT 50",UUID.class);
  for(var id:ids)tx.executeWithoutResult(s->{var r=locked(id);if("RUNNING".equals(r.get("state"))&&db.queryForObject("SELECT lease_until<=clock_timestamp() FROM business.tasks WHERE id=?",Boolean.class,id))retryOrFail(r,"LEASE_EXPIRED",false);});
  // Redis is a delivery hint. PostgreSQL remains the durable source after queue loss.
  db.update("UPDATE business.outbox o SET published_at=NULL FROM business.tasks t WHERE o.task_id=t.id AND t.state='QUEUED' AND t.available_at<=clock_timestamp() AND o.published_at<clock_timestamp()-interval '5 seconds'");
 }
 public void retry(UUID project,UUID owner,UUID id){projects.findByIdAndOwnerId(project,owner);tx.executeWithoutResult(s->{var r=locked(id);if(!project.equals(r.get("project_id")))throw new ApiFailure(404,"TASK_NOT_FOUND","任务不存在。");if(!"FAILED".equals(r.get("document_state"))||!"FAILED".equals(r.get("state"))||(r.get("error_code")!=null&&PERMANENT.contains(r.get("error_code"))))throw new ApiFailure(409,"TASK_NOT_RETRYABLE","请修正原文件后重新上传。");enqueue(r,0,0);db.update("UPDATE business.documents SET state='PROCESSING',error_code=NULL,error_message=NULL WHERE id=?",r.get("document_id"));});}
}
