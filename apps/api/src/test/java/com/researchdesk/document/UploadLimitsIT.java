package com.researchdesk.document;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit real Compose acceptance after test-upload-limits.ps1 sets 1KiB / 2 pages / 2 documents. */
class UploadLimitsIT {
 private final UploadIT fixture=new UploadIT();
 @AfterEach void cleanup() throws Exception {fixture.cleanup();}
 @Test void deploymentCanTightenBytePageAndProjectLimits() throws Exception {
  var session=fixture.register();String project=fixture.project(session);
  fixture.error(fixture.upload(project,"large.md",new byte[1025],session),413,"UPLOAD_TOO_LARGE");
  fixture.error(fixture.upload(project,"three.pdf",fixture.pdf(3,false,false),session),400,"PDF_PAGE_LIMIT");
  assertThat(fixture.upload(project,"one.md","# One".getBytes(StandardCharsets.UTF_8),session).status()).isEqualTo(201);
  assertThat(fixture.upload(project,"two.md","# Two".getBytes(StandardCharsets.UTF_8),session).status()).isEqualTo(201);
  fixture.error(fixture.upload(project,"three.md","# Three".getBytes(StandardCharsets.UTF_8),session),409,"DOCUMENT_QUOTA");
  assertThat(fixture.request("GET","/api/projects/"+project+"/documents",null,session).body()).hasSize(2);
 }
}
