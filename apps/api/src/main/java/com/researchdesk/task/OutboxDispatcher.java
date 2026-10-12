package com.researchdesk.task;

import com.researchdesk.document.ObjectStorage;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxDispatcher {
 private static final Logger LOG=LoggerFactory.getLogger(OutboxDispatcher.class);
 private final JdbcTemplate db;private final StringRedisTemplate redis;private final TaskService tasks;private final ObjectStorage storage;private final TransactionTemplate tx;
 public OutboxDispatcher(JdbcTemplate db,StringRedisTemplate redis,TaskService tasks,ObjectStorage storage,PlatformTransactionManager manager){this.db=db;this.redis=redis;this.tasks=tasks;this.storage=storage;tx=new TransactionTemplate(manager);}
 @Scheduled(fixedDelay=1000) public void dispatch(){try{
  tasks.recover();
  for(var row:db.queryForList("SELECT o.id,o.payload::text payload FROM business.outbox o JOIN business.tasks t ON t.id=o.task_id WHERE o.published_at IS NULL AND o.available_at<=clock_timestamp() AND t.state='QUEUED' ORDER BY o.available_at,o.id LIMIT 50")){
   redis.opsForStream().add("researchdesk:ingestion",Map.of("payload",row.get("payload").toString()));
   db.update("UPDATE business.outbox SET published_at=clock_timestamp() WHERE id=? AND payload=?::jsonb",row.get("id"),row.get("payload"));
  }
 }catch(RuntimeException e){LOG.warn("Ingestion dispatch unavailable: {}",e.getClass().getSimpleName());}}
 @Scheduled(fixedDelay=5000) public void cleanup(){try{
  for(var key:db.queryForList("SELECT object_key FROM business.storage_cleanup WHERE not_before<=clock_timestamp() ORDER BY not_before LIMIT 20",String.class)){
   try{tx.executeWithoutResult(s->{
    // Upload holds the same lock from its durable intent through promotion/compensation.
    if(!Boolean.TRUE.equals(db.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))",Boolean.class,key)))return;
    if(db.queryForList("SELECT object_key FROM business.storage_cleanup WHERE object_key=? AND not_before<=clock_timestamp() FOR UPDATE",key).isEmpty())return;
    if(db.queryForObject("SELECT count(*) FROM business.document_versions WHERE object_key=?",Integer.class,key)==0)storage.delete(key);
    db.update("DELETE FROM business.storage_cleanup WHERE object_key=?",key);
   });}catch(RuntimeException e){db.update("UPDATE business.storage_cleanup SET not_before=clock_timestamp()+interval '10 seconds' WHERE object_key=?",key);LOG.warn("Storage cleanup deferred: {}",e.getClass().getSimpleName());}
  }
 }catch(RuntimeException e){LOG.warn("Storage reconciliation unavailable: {}",e.getClass().getSimpleName());}}
}
