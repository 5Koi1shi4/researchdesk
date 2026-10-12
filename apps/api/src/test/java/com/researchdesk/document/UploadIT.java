package com.researchdesk.document;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.font.*;

class UploadIT {
    static final ObjectMapper JSON=new ObjectMapper();
    final HttpClient http=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
    final String base=System.getenv().getOrDefault("ACCEPTANCE_URL","http://localhost:18080");
    final List<String> emails=new ArrayList<>();
    record Session(String cookie,String csrf) {}
    record Result(int status,JsonNode body) {}
    String sql(String query) throws Exception {
        var process=new ProcessBuilder("docker","compose","-f",Path.of(System.getProperty("user.dir"),"../../infra/compose.yaml").normalize().toString(),"exec","-T","postgres","psql","-U","researchdesk","-d","researchdesk","-v","ON_ERROR_STOP=1","-At","-c",query).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero(); return output.strip();
    }
    Result request(String method,String path,String body,Session session) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(30)).header("Content-Type","application/json");
        if(session!=null) b.header("Cookie",session.cookie()).header("X-CSRF-Token",session.csrf());
        var r=http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        return new Result(r.statusCode(),r.body().isBlank()?JSON.nullNode():JSON.readTree(r.body()));
    }
    Session register() throws Exception {
        String email="upload-"+UUID.randomUUID()+"@researchdesk.test"; emails.add(email);
        var r=http.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/register")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+email+"\",\"password\":\"CorrectHorseBattery9!\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(201);
        return new Session(r.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0],JSON.readTree(r.body()).get("csrfToken").asText());
    }
    String project(Session s) throws Exception { return request("POST","/api/projects","{\"name\":\"Upload acceptance\"}",s).body().get("id").asText(); }
    Result upload(String id,String name,byte[] bytes,Session s) throws Exception {
        String boundary="rd-"+UUID.randomUUID();
        byte[] prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
        var r=http.send(HttpRequest.newBuilder(URI.create(base+"/api/projects/"+id+"/documents")).timeout(java.time.Duration.ofSeconds(30)).header("Cookie",s.cookie()).header("X-CSRF-Token",s.csrf()).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),HttpRequest.BodyPublishers.ofByteArray(bytes),HttpRequest.BodyPublishers.ofByteArray(suffix))).build(),HttpResponse.BodyHandlers.ofString());
        return new Result(r.statusCode(),r.body().startsWith("{")?JSON.readTree(r.body()):JSON.nullNode());
    }
    byte[] pdf(int pages,boolean encrypted,boolean text) throws Exception {
        try(var d=new PDDocument();var out=new java.io.ByteArrayOutputStream()) {
            for(int i=0;i<pages;i++) { var p=new PDPage();d.addPage(p);if(text)try(var c=new PDPageContentStream(d,p)){c.beginText();c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);c.newLineAtOffset(50,700);c.showText("Research fixture page "+i);c.endText();} }
            if(encrypted)d.protect(new StandardProtectionPolicy("owner-secret","reader-secret",new AccessPermission()));
            d.save(out);return out.toByteArray();
        }
    }
    void error(Result r,int status,String code) { assertThat(r.status()).isEqualTo(status);assertThat(r.body().path("code").asText()).isEqualTo(code);assertThat(r.body().get("requestId").asText()).isNotBlank(); }
    String minio(String command) throws Exception {
        var process=new ProcessBuilder("docker","compose","-f",Path.of(System.getProperty("user.dir"),"../../infra/compose.yaml").normalize().toString(),"exec","-T","minio","sh","-c","mc alias set local http://localhost:9000 \"$MINIO_ROOT_USER\" \"$MINIO_ROOT_PASSWORD\" >/dev/null && "+command).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertThat(process.waitFor()).as(output).isZero();return output.strip();
    }
    void storageService(String action) throws Exception {
        var process=new ProcessBuilder("docker","compose","-f",Path.of(System.getProperty("user.dir"),"../../infra/compose.yaml").normalize().toString(),action,"minio").redirectErrorStream(true).start();String out=new String(process.getInputStream().readAllBytes());assertThat(process.waitFor()).as(out).isZero();
    }
    @AfterEach void cleanup() throws Exception {
        for(String email:emails) {
            String projects=sql("SELECT id FROM business.projects WHERE owner_id=(SELECT id FROM business.users WHERE email='"+email+"')");
            for(String id:projects.split("\\R"))if(!id.isBlank()){minio("mc rm --recursive --force local/researchdesk-documents/projects/"+id+"/");sql("DELETE FROM business.storage_cleanup WHERE object_key LIKE 'projects/"+id+"/%'");}
            sql("DELETE FROM business.users WHERE email='"+email+"'; DELETE FROM business.auth_throttles WHERE key='email:"+email+"';");
        }
    }
    @Test void objectStorageFailureCreatesNoDocumentTaskOrOutboxAndPreservesCleanupIntent() throws Exception {
        var s=register();String p=project(s);
        try {
            storageService("stop");error(upload(p,"storage.md","# Storage outage".getBytes(StandardCharsets.UTF_8),s),503,"STORAGE_UNAVAILABLE");
            assertThat(sql("SELECT count(*) FROM business.documents WHERE project_id='"+p+"'")).isEqualTo("0");
            assertThat(sql("SELECT count(*) FROM business.tasks WHERE project_id='"+p+"'")).isEqualTo("0");
            assertThat(sql("SELECT count(*) FROM business.storage_cleanup WHERE object_key LIKE 'projects/"+p+"/%'")).isEqualTo("1");
        } finally {storageService("start");}
    }
    @Test void outboxTransactionFailureRollsBackRecordsAndCompensatesRealObject() throws Exception {
        var s=register();String p=project(s);
        sql("CREATE FUNCTION business.upload_it_reject_"+p.replace("-", "")+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.payload->>'projectId'='"+p+"' THEN RAISE EXCEPTION 'owned fixture outbox failure'; END IF; RETURN NEW; END $$; CREATE TRIGGER upload_it_reject_"+p.replace("-", "")+" BEFORE INSERT ON business.outbox FOR EACH ROW EXECUTE FUNCTION business.upload_it_reject_"+p.replace("-", "")+"()");
        try {
            error(upload(p,"rollback.md","# Atomic failure".getBytes(StandardCharsets.UTF_8),s),503,"UPLOAD_UNAVAILABLE");
            assertThat(request("GET","/api/projects/"+p+"/documents",null,s).body()).isEmpty();
            assertThat(sql("SELECT count(*) FROM business.tasks WHERE project_id='"+p+"'")).isEqualTo("0");
            assertThat(sql("SELECT count(*) FROM business.storage_cleanup WHERE object_key LIKE 'projects/"+p+"/%'")).isEqualTo("0");
            assertThat(minio("mc ls --recursive local/researchdesk-documents/projects/"+p+"/")).isEmpty();
        }finally{sql("DROP TRIGGER upload_it_reject_"+p.replace("-", "")+" ON business.outbox; DROP FUNCTION business.upload_it_reject_"+p.replace("-", "")+"()");}
    }
    @Test void rolledBackWrittenObjectRemainsInDurableCleanupQueueWhenMinioCompensationFails() throws Exception {
        var session=register();String project=project(session),suffix=project.replace("-", "");
        sql("CREATE FUNCTION business.upload_it_pause_"+suffix+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.payload->>'projectId'='"+project+"' THEN PERFORM pg_sleep(5); RAISE EXCEPTION 'owned fixture rollback'; END IF; RETURN NEW; END $$; CREATE TRIGGER upload_it_pause_"+suffix+" BEFORE INSERT ON business.outbox FOR EACH ROW EXECUTE FUNCTION business.upload_it_pause_"+suffix+"()");
        boolean paused=false;String key=null;
        try {
            var result=CompletableFuture.supplyAsync(()->{try{return upload(project,"orphan.md","# Durable orphan".getBytes(StandardCharsets.UTF_8),session);}catch(Exception e){throw new RuntimeException(e);}});
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
            while(minio("mc ls --recursive local/researchdesk-documents/projects/"+project+"/").isEmpty()) {assertThat(System.nanoTime()).as("object written before rollback pause").isLessThan(deadline);Thread.sleep(50);}
            key=sql("SELECT object_key FROM business.storage_cleanup WHERE object_key LIKE 'projects/"+project+"/%'");assertThat(key).startsWith("projects/"+project+"/");
            storageService("pause");paused=true;
            error(result.get(30,TimeUnit.SECONDS),503,"UPLOAD_UNAVAILABLE");
            assertThat(sql("SELECT count(*) FROM business.documents WHERE project_id='"+project+"'")).isEqualTo("0");
            assertThat(sql("SELECT count(*) FROM business.tasks WHERE project_id='"+project+"'")).isEqualTo("0");
            assertThat(sql("SELECT count(*) FROM business.storage_cleanup WHERE object_key='"+key+"'")).isEqualTo("1");
        }finally{if(paused)storageService("unpause");sql("DROP TRIGGER upload_it_pause_"+suffix+" ON business.outbox; DROP FUNCTION business.upload_it_pause_"+suffix+"()");}
        assertThat(minio("mc cat local/researchdesk-documents/"+key)).isEqualTo("# Durable orphan");
    }
    @Test void exactTwentyMiBIsAcceptedAndLargeEdgeRequestsReturnJson() throws Exception {
        var s=register();String p=project(s);byte[] text=new byte[20*1024*1024];Arrays.fill(text,(byte)'a');
        var accepted=upload(p,"limit.md",text,s);assertThat(accepted.status()).as(accepted.body().toString()).isEqualTo(201);
        error(upload(p,"edge.md",new byte[23*1024*1024],s),413,"UPLOAD_TOO_LARGE");
        assertThat(request("GET","/api/projects/"+p+"/documents",null,s).body()).hasSize(1);
    }
    @Test void uploadRequiresCsrfAndProjectOwnershipAndDigestIsScopedToProject() throws Exception {
        var s=register();String p=project(s);byte[] text="# Scope".getBytes(StandardCharsets.UTF_8);
        error(upload(p,"scope.md",text,new Session(s.cookie(),"")),403,"CSRF_REQUIRED");
        var foreign=register();error(upload(p,"scope.md",text,foreign),404,"PROJECT_NOT_FOUND");
        var a=upload(p,"scope.md",text,s);var b=upload(project(s),"scope.md",text,s);
        assertThat(a.status()).isEqualTo(201);assertThat(b.status()).isEqualTo(201);assertThat(a.body().get("documentId")).isNotEqualTo(b.body().get("documentId"));
    }
    @Test void oversizedUploadReturnsStable413() throws Exception {var s=register(); error(upload(project(s),"large.md",new byte[20*1024*1024+1],s),413,"UPLOAD_TOO_LARGE");}
    @Test void validatesActualBytesAndPdfMetadata() throws Exception {
        var s=register();String p=project(s);
        error(upload(p,"fake.pdf","# Markdown".getBytes(StandardCharsets.UTF_8),s),400,"INVALID_DOCUMENT_TYPE");
        error(upload(p,"bad.md",new byte[]{(byte)0xc3,0x28},s),400,"INVALID_UTF8");
        error(upload(p,"secret.pdf",pdf(1,true,true),s),400,"ENCRYPTED_PDF");
        error(upload(p,"broken.pdf","%PDF-1.7\ninvalid".getBytes(StandardCharsets.UTF_8),s),400,"INVALID_PDF");
        error(upload(p,"many.pdf",pdf(201,false,true),s),400,"PDF_PAGE_LIMIT");
        error(upload(p,"scan.pdf",pdf(1,false,false),s),400,"DOCUMENT_NO_TEXT");
        var valid=upload(p,"valid.pdf",pdf(200,false,true),s);assertThat(valid.status()).as(valid.body().toString()).isEqualTo(201);
    }
    @Test void sameProjectConcurrentUploadsShareOneDocumentTaskAndOutbox() throws Exception {
        var s=register();String p=project(s);byte[] content="# Same document".getBytes(StandardCharsets.UTF_8);
        var a=CompletableFuture.supplyAsync(()->{try{return upload(p,"a.md",content,s);}catch(Exception e){throw new RuntimeException(e);}});
        var b=CompletableFuture.supplyAsync(()->{try{return upload(p,"b.md",content,s);}catch(Exception e){throw new RuntimeException(e);}});
        var ra=a.get();var rb=b.get();assertThat(ra.status()).isIn(200,201);assertThat(rb.status()).isIn(200,201);
        assertThat(ra.body()).isEqualTo(rb.body());assertThat(ra.body().get("state").asText()).isEqualTo("PROCESSING");
        assertThat(request("GET","/api/projects/"+p+"/documents",null,s).body()).hasSize(1);
        String key=sql("SELECT object_key FROM business.document_versions WHERE project_id='"+p+"'");
        assertThat(minio("mc cat local/researchdesk-documents/"+key)).isEqualTo("# Same document");
        assertThat(sql("SELECT count(*) FROM business.storage_cleanup WHERE object_key LIKE 'projects/"+p+"/%'")).isEqualTo("0");
        assertThat(minio("mc ls --recursive local/researchdesk-documents/projects/"+p+"/").lines().count()).isEqualTo(1);
        String t=ra.body().get("taskId").asText();
        assertThat(request("GET","/api/projects/"+p+"/tasks/"+t,null,s).body().get("state").asText()).isEqualTo("QUEUED");
        assertThat(sql("SELECT count(*) FROM business.outbox WHERE task_id='"+t+"'")).isEqualTo("1");
        var foreign=register(); error(request("GET","/api/projects/"+p+"/documents",null,foreign),404,"PROJECT_NOT_FOUND");
        error(request("GET","/api/projects/"+p+"/tasks/"+t,null,foreign),404,"PROJECT_NOT_FOUND");
        assertThat(request("GET","/api/projects/"+p+"/documents",null,s).body().get(0).has("objectKey")).isFalse();
    }
    @Test void fiftyDocumentsAreAllowedAndFiftyFirstRejectedIncludingConcurrentBoundary() throws Exception {
        var s=register();String p=project(s);
        for(int i=0;i<49;i++)assertThat(upload(p,"source.md",("# Source "+i).getBytes(StandardCharsets.UTF_8),s).status()).isEqualTo(201);
        var a=CompletableFuture.supplyAsync(()->{try{return upload(p,"last.md","# Last A".getBytes(StandardCharsets.UTF_8),s);}catch(Exception e){throw new RuntimeException(e);}});
        var b=CompletableFuture.supplyAsync(()->{try{return upload(p,"last.md","# Last B".getBytes(StandardCharsets.UTF_8),s);}catch(Exception e){throw new RuntimeException(e);}});
        assertThat(List.of(a.get().status(),b.get().status())).containsExactlyInAnyOrder(201,409);
        error(upload(p,"extra.md","# Extra".getBytes(StandardCharsets.UTF_8),s),409,"DOCUMENT_QUOTA");
        assertThat(request("GET","/api/projects/"+p+"/documents",null,s).body()).hasSize(50);
        assertThat(upload(p,"duplicate.md","# Source 0".getBytes(StandardCharsets.UTF_8),s).status()).isEqualTo(200);
    }
}
