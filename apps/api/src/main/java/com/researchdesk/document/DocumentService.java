package com.researchdesk.document;

import com.researchdesk.Contracts;
import com.researchdesk.Contracts.DocumentState;
import com.researchdesk.Contracts.Document;
import com.researchdesk.Contracts.DocumentUpload;
import com.researchdesk.Contracts.DocumentTask;
import com.researchdesk.Contracts.TaskState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchdesk.identity.ApiFailure;
import com.researchdesk.project.ProjectService;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
 public record Accepted(DocumentUpload upload,boolean duplicate) {}
 private final JdbcTemplate db;private final TransactionTemplate tx;private final ProjectService projects;private final ObjectStorage storage;private final UploadValidator validator;private final ObjectMapper json;private final int quota;
 public DocumentService(JdbcTemplate db,PlatformTransactionManager manager,ProjectService projects,ObjectStorage storage,UploadValidator validator,ObjectMapper json,@Value("${MAX_DOCUMENTS_PER_PROJECT:50}") int quota) {
  if(quota<1||quota>50)throw new IllegalArgumentException("Document quota may only tighten default");
  this.db=db;tx=new TransactionTemplate(manager);this.projects=projects;this.storage=storage;this.validator=validator;this.json=json;this.quota=quota;
 }
 public Accepted upload(UUID project,UUID owner,MultipartFile file) {
  projects.findByIdAndOwnerId(project,owner);
  try(var valid=validator.validate(file)){
   UUID version=UUID.randomUUID(),document=UUID.randomUUID(),task=UUID.randomUUID(),attempt=UUID.randomUUID();
   String key="projects/"+project+"/versions/"+version;
   try(var objectLock=lockObject(key)){
   // Autocommitted before object storage. A crash or rollback leaves a durable cleanup entry.
   db.update("INSERT INTO business.storage_cleanup(object_key) VALUES (?)",key);
   try {
    storage.put(key,valid.path(),valid.mediaType());
    Accepted result=tx.execute(status->{
     db.execute("SET LOCAL lock_timeout='5s'");
     if(db.queryForList("SELECT id FROM business.projects WHERE id=? AND owner_id=? FOR UPDATE",project,owner).isEmpty())throw new ApiFailure(404,"PROJECT_NOT_FOUND","项目不存在。");
     var existing=db.query("SELECT d.id,v.id,t.id,d.state FROM business.documents d JOIN business.document_versions v ON v.document_id=d.id JOIN business.tasks t ON t.document_version_id=v.id WHERE d.project_id=? AND d.content_sha256=? AND d.state NOT IN ('DELETING','DELETED')",(r,i)->new DocumentUpload(r.getString(1),r.getString(2),r.getString(3),DocumentState.valueOf(r.getString(4))),project,valid.digest());
     if(!existing.isEmpty())return new Accepted(existing.get(0),true);
     if(db.queryForObject("SELECT count(*) FROM business.documents WHERE project_id=? AND state<>'DELETED'",Integer.class,project)>=quota)throw new ApiFailure(409,"DOCUMENT_QUOTA","项目资料数量已达上限（最多 "+quota+" 份），请使用其他项目。");
     db.update("INSERT INTO business.documents(id,project_id,title,content_sha256,state) VALUES (?,?,?,?,'PROCESSING')",document,project,valid.title(),valid.digest());
     db.update("INSERT INTO business.document_versions(id,document_id,project_id,object_key,media_type,size_bytes,page_count,parser_version) VALUES (?,?,?,?,?,?,?,'pypdf-6.12.2-markdown-v1')",version,document,project,key,valid.mediaType(),valid.size(),valid.pages());
     db.update("INSERT INTO business.tasks(id,project_id,document_version_id,attempt_id,state) VALUES (?,?,?,?,'QUEUED')",task,project,version,attempt);
     try {
      String payload=json.writeValueAsString(new Contracts.InternalTask(task.toString(),attempt.toString(),1,project.toString(),version.toString(),key,"pypdf-6.12.2-markdown-v1"));
      db.update("INSERT INTO business.outbox(id,task_id,event_type,payload) VALUES (?,?,'DOCUMENT_PARSE_REQUESTED',?::jsonb)",UUID.randomUUID(),task,payload);
     }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
     db.update("DELETE FROM business.storage_cleanup WHERE object_key=?",key);
     return new Accepted(new DocumentUpload(document.toString(),version.toString(),task.toString(),DocumentState.PROCESSING),false);
    });
    if(result.duplicate())compensate(key);
    return result;
   }catch(RuntimeException e){compensate(key);if(e instanceof ApiFailure)throw e;throw new ApiFailure(503,"UPLOAD_UNAVAILABLE","资料保存暂时失败，请稍后重新上传。");}
   }
  }catch(java.io.IOException e){throw new ApiFailure(503,"UPLOAD_UNAVAILABLE","上传暂时不可用，请稍后重试。");}
 }
 private ObjectLock lockObject(String key){try{return new ObjectLock(Objects.requireNonNull(db.getDataSource()).getConnection(),key);}catch(java.sql.SQLException e){throw new ApiFailure(503,"UPLOAD_UNAVAILABLE","上传暂时不可用，请稍后重试。");}}
 private static final class ObjectLock implements AutoCloseable {
  private final java.sql.Connection connection;private final String key;
  ObjectLock(java.sql.Connection connection,String key)throws java.sql.SQLException{this.connection=connection;this.key=key;try(var q=connection.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?,0))")){q.setString(1,key);q.execute();}catch(java.sql.SQLException e){connection.close();throw e;}}
  public void close(){try{try(var q=connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?,0))")){q.setString(1,key);q.execute();}}catch(java.sql.SQLException e){try{connection.abort(Runnable::run);}catch(java.sql.SQLException ignored){}}finally{try{connection.close();}catch(java.sql.SQLException ignored){}}}
 }
 private void compensate(String key) {try{if(db.queryForObject("SELECT count(*) FROM business.document_versions WHERE object_key=?",Integer.class,key)>0){db.update("DELETE FROM business.storage_cleanup WHERE object_key=?",key);return;}storage.delete(key);db.update("DELETE FROM business.storage_cleanup WHERE object_key=?",key);}catch(RuntimeException ignored){/* Durable intent remains available for later cleanup. */}}
 public List<Document> list(UUID project,UUID owner){
  projects.findByIdAndOwnerId(project,owner);
  return db.query("SELECT d.id document_id,v.id version_id,t.id task_id,d.title,d.state,d.error_code,d.error_message,v.size_bytes,v.page_count,d.created_at FROM business.documents d JOIN business.document_versions v ON v.document_id=d.id JOIN business.tasks t ON t.document_version_id=v.id WHERE d.project_id=? AND d.state<>'DELETED' ORDER BY d.created_at DESC,d.id",
   (r,i)->new Document(r.getString("document_id"),r.getString("version_id"),r.getString("task_id"),r.getString("title"),DocumentState.valueOf(r.getString("state")),r.getString("error_code"),r.getString("error_message"),r.getLong("size_bytes"),r.getObject("page_count",Integer.class),r.getTimestamp("created_at").toInstant().toString()),project);
 }
 public DocumentTask task(UUID project,UUID owner,UUID id){
  projects.findByIdAndOwnerId(project,owner);
  return db.query("SELECT id,document_version_id,state,error_code,error_message FROM business.tasks WHERE project_id=? AND id=?",(r,i)->new DocumentTask(r.getString("id"),r.getString("document_version_id"),TaskState.valueOf(r.getString("state")),r.getString("error_code"),r.getString("error_message")),project,id).stream().findFirst().orElseThrow(()->new ApiFailure(404,"TASK_NOT_FOUND","任务不存在。"));
 }
}
