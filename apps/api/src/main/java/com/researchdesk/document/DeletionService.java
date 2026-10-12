package com.researchdesk.document;

import com.researchdesk.identity.ApiFailure;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DeletionService {
 public record Result(String documentId,String state) {}
 private final JdbcTemplate db;private final TransactionTemplate tx;private final ObjectStorage storage;
 public DeletionService(JdbcTemplate db,PlatformTransactionManager manager,ObjectStorage storage){this.db=db;this.tx=new TransactionTemplate(manager);this.storage=storage;}
 public Result delete(UUID document,UUID owner){return tx.execute(s->{
  var rows=db.queryForList("SELECT d.state FROM business.documents d JOIN business.projects p ON p.id=d.project_id WHERE d.id=? AND p.owner_id=? FOR UPDATE OF d",document,owner);
  if(rows.isEmpty())throw new ApiFailure(404,"SOURCE_NOT_FOUND","资料不存在。");
  String state=rows.getFirst().get("state").toString();
  if(state.equals("DELETED"))return new Result(document.toString(),state);
  // The existing trigger invalidates publication and cancels active attempts in this transaction.
  db.update("UPDATE business.documents SET state='DELETING' WHERE id=? AND state<>'DELETING'",document);
  db.update("INSERT INTO business.document_deletions(document_id) VALUES (?) ON CONFLICT DO NOTHING",document);
  return new Result(document.toString(),"DELETING");
 });}
 @Scheduled(fixedDelay=1000) public void cleanup(){
  for(UUID id:db.queryForList("SELECT document_id FROM business.document_deletions WHERE completed_at IS NULL AND next_attempt_at<=clock_timestamp() ORDER BY next_attempt_at LIMIT 10",UUID.class)){
   try{tx.executeWithoutResult(s->{
    // Same document-before-task/index lock order as completion/deletion; no worker can republish.
    if(db.queryForList("SELECT id FROM business.documents WHERE id=? AND state='DELETING' FOR UPDATE SKIP LOCKED",id).isEmpty())return;
    db.queryForList("SELECT business.purge_deleted_index(?)",id);
    for(String key:db.queryForList("SELECT object_key FROM business.document_versions WHERE document_id=?",String.class,id))storage.delete(key);
    db.update("UPDATE business.documents SET state='DELETED' WHERE id=?",id);
    db.update("UPDATE business.document_deletions SET completed_at=clock_timestamp() WHERE document_id=?",id);
   });}catch(RuntimeException e){db.update("UPDATE business.document_deletions SET attempts=attempts+1,next_attempt_at=clock_timestamp()+interval '10 seconds' WHERE document_id=? AND completed_at IS NULL",id);}
  }
 }
}
