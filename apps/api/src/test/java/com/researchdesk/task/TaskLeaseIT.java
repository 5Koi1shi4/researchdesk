package com.researchdesk.task;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TaskLeaseIT {
 String compose=Path.of(System.getProperty("user.dir"),"../../infra/compose.yaml").normalize().toString();
 String run(String service,String... command) throws Exception {
  var args=new java.util.ArrayList<String>(java.util.List.of("docker","compose","-f",compose,"exec","-T",service));args.addAll(java.util.List.of(command));
  var p=new ProcessBuilder(args).redirectErrorStream(true).start();String out=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertThat(p.waitFor()).as(out).isZero();return out.strip();
 }
 String sql(String s)throws Exception{return run("postgres","psql","-U","researchdesk","-d","researchdesk","-v","ON_ERROR_STOP=1","-At","-c",s);}
 @Test void claimsRejectDuplicateStaleCompletionAndRecoverExpiredLease() throws Exception {
  String u=UUID.randomUUID().toString(),p=UUID.randomUUID().toString(),d=UUID.randomUUID().toString(),v=UUID.randomUUID().toString(),t=UUID.randomUUID().toString(),a=UUID.randomUUID().toString();
  sql("INSERT INTO business.users(id,email,password_hash) VALUES ('"+u+"','lease-"+u+"@test','unused'); INSERT INTO business.projects(id,owner_id,name) VALUES ('"+p+"','"+u+"','Lease fixture'); INSERT INTO business.documents(id,project_id,title,content_sha256,state) VALUES ('"+d+"','"+p+"','Lease','"+"a".repeat(64)+"','PROCESSING'); INSERT INTO business.document_versions(id,document_id,project_id,object_key,media_type,size_bytes,parser_version) VALUES ('"+v+"','"+d+"','"+p+"','lease-fixture/"+v+"','text/markdown',1,'test'); INSERT INTO business.tasks(id,project_id,document_version_id,attempt_id,state) VALUES ('"+t+"','"+p+"','"+v+"','"+a+"','QUEUED')");
  try {
   String code="import json,os,urllib.request,urllib.error\n"+
    "def call(path,token=True):\n headers={'Content-Type':'application/json'}\n if token: headers['Authorization']='Bearer '+os.environ['INTERNAL_SERVICE_TOKEN']\n req=urllib.request.Request('http://api:8080/internal/tasks/"+t+"/'+path,data=json.dumps({'attemptId':'"+a+"','fence':1}).encode(),headers=headers)\n try:\n  with urllib.request.urlopen(req,timeout=10) as r: return r.status\n except urllib.error.HTTPError as e: return e.code\n"+
    "assert call('claim',False)==401\nassert call('claim')==200\nassert call('claim')==409\nassert call('complete')==409\nprint('PASS authenticated claim, duplicate and unpublished complete')";
   assertThat(run("ai","python","-c",code)).contains("PASS");
   sql("UPDATE business.tasks SET lease_until=now()-interval '1 minute' WHERE id='"+t+"'");
   long end=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();while(sql("SELECT fence FROM business.tasks WHERE id='"+t+"'").equals("1")){assertThat(System.nanoTime()).isLessThan(end);Thread.sleep(250);}
   assertThat(sql("SELECT state FROM business.documents WHERE id='"+d+"'")).isEqualTo("PROCESSING");
   assertThat(run("ai","python","-c",code.substring(0,code.indexOf("assert call"))+"assert call('complete')==409\nprint('PASS stale completion rejected')")).contains("PASS");
   assertThat(run("ai","python","-c","from ingestion.indexer import index_document,StaleAttempt\nfrom ingestion.parser import ParsedSection\ntry:\n index_document({'documentVersionId':'"+v+"','attemptId':'"+a+"','projectId':'"+p+"','fence':1},[ParsedSection('stale evidence',None,1)])\n raise AssertionError('expired worker published')\nexcept StaleAttempt: print('PASS expired worker cannot publish')")).contains("PASS");
  }finally{sql("DO $$ BEGIN IF to_regclass('retrieval.index_versions') IS NOT NULL THEN EXECUTE 'DELETE FROM retrieval.index_versions WHERE document_version_id='''||'"+v+"'||''''; END IF; END $$; DELETE FROM business.users WHERE id='"+u+"'");}
 }
}
