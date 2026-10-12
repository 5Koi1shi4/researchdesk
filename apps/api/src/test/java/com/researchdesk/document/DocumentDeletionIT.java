package com.researchdesk.document;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;
class DocumentDeletionIT {
 static final ObjectMapper JSON=new ObjectMapper();
 final HttpClient http=HttpClient.newHttpClient();
 final String base=com.researchdesk.ComposeFixture.baseUrl();
 record Session(String cookie,String csrf) {}
 String sql(String query)throws Exception {var p=new ProcessBuilder("docker","compose","-p",com.researchdesk.ComposeFixture.project(),"-f",Path.of(System.getProperty("user.dir"),"../../infra/compose.yaml").normalize().toString(),"exec","-T","postgres","psql","-U","researchdesk","-d","researchdesk","-v","ON_ERROR_STOP=1","-At","-c",query).redirectErrorStream(true).start();String out=new String(p.getInputStream().readAllBytes());assertThat(p.waitFor()).as(out).isZero();return out.strip();}
 HttpResponse<String> request(String method,String path,String body,Session s)throws Exception {var b=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json");if(s!=null)b.header("Cookie",s.cookie()).header("X-CSRF-Token",s.csrf());return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
 Session register()throws Exception {var r=request("POST","/api/auth/register","{\"email\":\"source-"+UUID.randomUUID()+"@researchdesk.test\",\"password\":\"CorrectHorseBattery9!\"}",null);assertThat(r.statusCode()).isEqualTo(201);return new Session(r.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0],JSON.readTree(r.body()).get("csrfToken").asText());}
 JsonNode upload(Session s)throws Exception {String project=JSON.readTree(request("POST","/api/projects","{\"name\":\"Sources\"}",s).body()).get("id").asText();return upload(s,project); }
 JsonNode upload(Session s,String project)throws Exception {String text="--source\r\nContent-Disposition: form-data; name=\"file\"; filename=\"source.md\"\r\nContent-Type: text/markdown\r\n\r\n# Preview\n\nSecond paragraph.\r\n--source--\r\n";var r=http.send(HttpRequest.newBuilder(URI.create(base+"/api/projects/"+project+"/documents")).header("Cookie",s.cookie()).header("X-CSRF-Token",s.csrf()).header("Content-Type","multipart/form-data; boundary=source").POST(HttpRequest.BodyPublishers.ofString(text)).build(),HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).as(r.body()).isEqualTo(201);var doc=JSON.readTree(r.body());long end=System.nanoTime()+30_000_000_000L;while(!sql("SELECT state FROM business.documents WHERE id='"+doc.get("documentId").asText()+"'").equals("READY")){assertThat(System.nanoTime()).isLessThan(end);Thread.sleep(250);}return doc;}
 @Test void authorizesSourceAndRevokesOldCitationBeforeRetryablePhysicalCleanup()throws Exception {
  var s=register();var d=upload(s);String id=d.get("documentId").asText(),version=d.get("versionId").asText(),source="/api/documents/"+id+"/source";
  var response=request("GET",source,null,s);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(response.body()).contains("Second paragraph.");assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
  var foreign=register();assertThat(request("GET",source,null,foreign).statusCode()).isEqualTo(404);
  assertThat(request("DELETE","/api/documents/"+id,null,foreign).statusCode()).isEqualTo(404);
  assertThat(request("GET","/api/document-versions/"+version+"/source-info",null,s).statusCode()).isEqualTo(200);
  assertThat(request("DELETE","/api/documents/"+id,null,new Session(s.cookie(),"")).statusCode()).isEqualTo(403);
  String chunk=sql("SELECT chunk_id FROM retrieval.chunks WHERE document_version_id='"+version+"' LIMIT 1");
  String citations="{\"citations\":[{\"chunkId\":\""+chunk+"\",\"documentVersionId\":\""+version+"\",\"title\":\"source.md\",\"page\":null,\"paragraph\":1,\"excerpt\":\"old snapshot\",\"availability\":\"AVAILABLE\"}]}";
  assertThat(request("POST","/api/sources/availability",citations,foreign).statusCode()).isEqualTo(404);
  assertThat(request("DELETE","/api/documents/"+id,null,s).statusCode()).isEqualTo(202);
  assertThat(request("GET",source,null,s).statusCode()).isEqualTo(410);
  assertThat(sql("SELECT count(*) FROM retrieval.index_versions WHERE document_version_id='"+version+"' AND active AND published")).isEqualTo("0");
  var refreshed=JSON.readTree(request("POST","/api/sources/availability",citations,s).body()).get(0);assertThat(refreshed.get("availability").asText()).isEqualTo("DELETED");assertThat(refreshed.get("excerpt").isNull()).isTrue();
  assertThat(request("DELETE","/api/documents/"+id,null,s).statusCode()).isEqualTo(202);
  long end=System.nanoTime()+30_000_000_000L;while(!sql("SELECT state FROM business.documents WHERE id='"+id+"'").equals("DELETED")){assertThat(System.nanoTime()).isLessThan(end);Thread.sleep(250);}
  assertThat(sql("SELECT count(*) FROM retrieval.chunks WHERE document_version_id='"+version+"'")).isEqualTo("0");
 }
 @Test void deletedFailedTaskCannotBeRetriedAndStorageFailureLeavesDurableCleanup()throws Exception {
  var s=register();var d=upload(s);String id=d.get("documentId").asText(),version=d.get("versionId").asText(),task=d.get("taskId").asText();
  String project=sql("SELECT project_id FROM business.documents WHERE id='"+id+"'");
  sql("UPDATE business.documents SET state='FAILED' WHERE id='"+id+"'; UPDATE business.tasks SET state='FAILED',error_code='INGESTION_UNAVAILABLE' WHERE id='"+task+"'");
  assertThat(request("GET","/api/documents/"+id+"/source",null,s).statusCode()).isEqualTo(409);
  storage("stop");
  try {
   assertThat(request("DELETE","/api/documents/"+id,null,s).statusCode()).isEqualTo(202);
   assertThat(request("POST","/api/projects/"+project+"/tasks/"+task+"/retry","{}",s).statusCode()).isEqualTo(409);
   long end=System.nanoTime()+40_000_000_000L;
   while(sql("SELECT attempts FROM business.document_deletions WHERE document_id='"+id+"'").equals("0")){assertThat(System.nanoTime()).isLessThan(end);Thread.sleep(500);}
   assertThat(sql("SELECT state FROM business.documents WHERE id='"+id+"'")).isEqualTo("DELETING");
   assertThat(JSON.readTree(request("GET","/api/document-versions/"+version+"/source-info",null,s).body()).get("availability").asText()).isEqualTo("DELETED");
  }finally{storage("start");Thread.sleep(3000);}
  long end=System.nanoTime()+40_000_000_000L;
  while(!sql("SELECT state FROM business.documents WHERE id='"+id+"'").equals("DELETED")){assertThat(System.nanoTime()).isLessThan(end);Thread.sleep(500);}
  assertThat(request("POST","/api/projects/"+project+"/tasks/"+task+"/retry","{}",s).statusCode()).isEqualTo(409);
 }
 void storage(String action)throws Exception {var p=new ProcessBuilder("docker","compose","-p",com.researchdesk.ComposeFixture.project(),"-f",Path.of(System.getProperty("user.dir"),"../../infra/compose.yaml").normalize().toString(),action,"minio").redirectErrorStream(true).start();String out=new String(p.getInputStream().readAllBytes());assertThat(p.waitFor()).as(out).isZero();} @Test void sameContentCanBeUploadedAgainWithoutRevivingOldCitation()throws Exception {
  var s=register();var first=upload(s);String id=first.get("documentId").asText();String project=sql("SELECT project_id FROM business.documents WHERE id='"+id+"'");
  assertThat(request("DELETE","/api/documents/"+id,null,s).statusCode()).isEqualTo(202);
  var second=upload(s,project);assertThat(second.get("documentId").asText()).isNotEqualTo(id);
  assertThat(request("GET","/api/documents/"+id+"/source",null,s).statusCode()).isEqualTo(410);
 } @Test void pdfSourceAndPagePngAreAuthorizedBoundedAndRevoked()throws Exception {
  var s=register();String project=JSON.readTree(request("POST","/api/projects","{\"name\":\"PDF preview\"}",s).body()).get("id").asText();byte[] bytes;
  try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument();var out=new java.io.ByteArrayOutputStream()) {
   for(int i=1;i<=2;i++){var p=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(p);try(var c=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,p)){c.beginText();c.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);c.newLineAtOffset(50,700);c.showText("Preview page "+i);c.endText();}}
   pdf.save(out);bytes=out.toByteArray();
  }
  byte[] prefix="--pdf\r\nContent-Disposition: form-data; name=\"file\"; filename=\"preview.pdf\"\r\nContent-Type: application/pdf\r\n\r\n".getBytes(StandardCharsets.UTF_8);
  var upload=http.send(HttpRequest.newBuilder(URI.create(base+"/api/projects/"+project+"/documents")).header("Cookie",s.cookie()).header("X-CSRF-Token",s.csrf()).header("Content-Type","multipart/form-data; boundary=pdf").POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),HttpRequest.BodyPublishers.ofByteArray(bytes),HttpRequest.BodyPublishers.ofString("\r\n--pdf--\r\n"))).build(),HttpResponse.BodyHandlers.ofString());
  assertThat(upload.statusCode()).as(upload.body()).isEqualTo(201);String id=JSON.readTree(upload.body()).get("documentId").asText();
  long end=System.nanoTime()+30_000_000_000L;while(!sql("SELECT state FROM business.documents WHERE id='"+id+"'").equals("READY")){assertThat(System.nanoTime()).isLessThan(end);Thread.sleep(250);}
  var source=request("GET","/api/documents/"+id+"/source",null,s);assertThat(source.headers().firstValue("Content-Type").orElse("")).isEqualTo("application/pdf");assertThat(source.headers().firstValue("Content-Security-Policy").orElse("")).contains("sandbox");assertThat(source.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
  String path="/api/documents/"+id+"/source/pages/2";
  var png=http.send(HttpRequest.newBuilder(URI.create(base+path)).header("Cookie",s.cookie()).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertThat(png.statusCode()).isEqualTo(200);assertThat(png.headers().firstValue("Content-Type").orElse("")).isEqualTo("image/png");assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png.body())).getWidth()).isLessThanOrEqualTo(1600);
  assertThat(request("GET",path,null,register()).statusCode()).isEqualTo(404);
  assertThat(request("GET","/api/documents/"+id+"/source/pages/3",null,s).statusCode()).isEqualTo(400);
  assertThat(request("DELETE","/api/documents/"+id,null,s).statusCode()).isEqualTo(202);assertThat(request("GET",path,null,s).statusCode()).isEqualTo(410);
 }}
