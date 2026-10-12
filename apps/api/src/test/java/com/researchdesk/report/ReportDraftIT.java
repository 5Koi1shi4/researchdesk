package com.researchdesk.report;

import static org.assertj.core.api.Assertions.*;

import com.researchdesk.Contracts.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

@org.springframework.test.context.junit.jupiter.SpringJUnitConfig(ReportDraftIT.Config.class)
class ReportDraftIT {
  @org.springframework.context.annotation.Configuration
  @org.springframework.transaction.annotation.EnableTransactionManagement
  static class Config {
    @org.springframework.context.annotation.Bean
    javax.sql.DataSource dataSource() {
      return new org.springframework.jdbc.datasource.DriverManagerDataSource(
          com.researchdesk.ComposeFixture.databaseUrl(),
          "researchdesk_business",
          "researchdesk-business-dev-only");
    }

    @org.springframework.context.annotation.Bean
    org.springframework.jdbc.core.JdbcTemplate jdbc(javax.sql.DataSource source) {
      return new org.springframework.jdbc.core.JdbcTemplate(source);
    }

    @org.springframework.context.annotation.Bean
    org.springframework.transaction.PlatformTransactionManager manager(
        javax.sql.DataSource source) {
      return new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
    }

    @org.springframework.context.annotation.Bean
    com.researchdesk.project.ProjectService projects(
        org.springframework.jdbc.core.JdbcTemplate db) {
      return new com.researchdesk.project.ProjectService(db);
    }

    @org.springframework.context.annotation.Bean
    ReportService reports(
        org.springframework.jdbc.core.JdbcTemplate db,
        com.researchdesk.project.ProjectService projects) {
      return new ReportService(db, projects, new com.fasterxml.jackson.databind.ObjectMapper());
    }
  }

  @Autowired ReportService reports;
  UUID owner = UUID.randomUUID(),
      project = UUID.randomUUID(),
      foreign = UUID.randomUUID(),
      document = UUID.randomUUID(),
      version = UUID.randomUUID(),
      chunk = UUID.randomUUID();

  String sql(String query) throws Exception {
    var process =
        new ProcessBuilder(
                "docker",
                "compose",
                "-p",
                com.researchdesk.ComposeFixture.project(),
                "-f",
                java.nio.file.Path.of(System.getProperty("user.dir"), "../../infra/compose.yaml")
                    .normalize()
                    .toString(),
                "exec",
                "-T",
                "postgres",
                "psql",
                "-U",
                "researchdesk",
                "-d",
                "researchdesk",
                "-v",
                "ON_ERROR_STOP=1",
                "-At")
            .redirectErrorStream(true)
            .start();
    try (var input = process.getOutputStream()) {
      input.write(query.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    String output =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(output).isZero();
    return output.strip();
  }

  @BeforeEach
  void fixture() throws Exception {
    sql(
        "INSERT INTO business.users(id,email,password_hash) VALUES('"
            + owner
            + "','draft-"
            + owner
            + "@researchdesk.test','fixture'); INSERT INTO business.projects(id,owner_id,name)"
            + " VALUES('"
            + project
            + "','"
            + owner
            + "','draft'),('"
            + foreign
            + "','"
            + owner
            + "','foreign'); INSERT INTO"
            + " business.documents(id,project_id,title,content_sha256,state) VALUES('"
            + document
            + "','"
            + project
            + "','Canonical title','"
            + "a".repeat(64)
            + "','READY'); INSERT INTO"
            + " business.document_versions(id,document_id,project_id,object_key,media_type,size_bytes,parser_version)"
            + " VALUES('"
            + version
            + "','"
            + document
            + "','"
            + project
            + "','draft/"
            + version
            + "','text/markdown',10,'sections-v1'); INSERT INTO"
            + " retrieval.index_versions(document_version_id,project_id,attempt_id,fence,lease_until,published,chunk_count)"
            + " VALUES('"
            + version
            + "','"
            + project
            + "','"
            + UUID.randomUUID()
            + "',1,now()+interval '1 hour',true,1); INSERT INTO"
            + " retrieval.chunks(document_version_id,fence,ordinal,chunk_id,text,page,paragraph,embedding,embedding_model)"
            + " VALUES('"
            + version
            + "',1,0,'"
            + chunk
            + "','Canonical evidence',NULL,1,'["
            + String.join(",", Collections.nCopies(32, "0.1"))
            + "]','deterministic-demo-sha256-v1');");
  }

  @AfterEach
  void cleanup() throws Exception {
    sql(
        "DELETE FROM retrieval.index_versions WHERE document_version_id='"
            + version
            + "';DELETE FROM business.users WHERE id='"
            + owner
            + "';");
  }

  Citation forged() {
    return new Citation(
        chunk.toString(),
        version.toString(),
        "forged title",
        99,
        99,
        "forged excerpt",
        Availability.AVAILABLE);
  }

  @Test
  void completionCanonicalizesAndIsIdempotentPerRunButNewRunCreatesNewDraft() {
    UUID run = UUID.randomUUID();
    String md = "研究 [[cite:" + chunk + "]]";
    var first =
        reports.completeDraft(
            project, owner, run, "报告", md, List.of(forged()), List.of(version), true);
    assertThat(first.citations().getFirst().title()).isEqualTo("Canonical title");
    assertThat(first.citations().getFirst().excerpt()).isEqualTo("Canonical evidence");
    assertThat(
            reports
                .completeDraft(
                    project, owner, run, "报告", md, List.of(forged()), List.of(version), true)
                .reportId())
        .isEqualTo(first.reportId());
    var second =
        reports.completeDraft(
            project, owner, UUID.randomUUID(), "报告", md, List.of(forged()), List.of(version), true);
    assertThat(second.reportId()).isNotEqualTo(first.reportId());
    assertThat(first.demo()).isTrue();
  }

  @Test
  void foreignProjectAndUnauthorizedOrDeletedVersionCannotBeCited() throws Exception {
    String md = "[[cite:" + chunk + "]]";
    assertThatThrownBy(
            () ->
                reports.completeDraft(
                    foreign,
                    owner,
                    UUID.randomUUID(),
                    "报告",
                    md,
                    List.of(forged()),
                    List.of(version),
                    true))
        .hasMessageContaining("引用");
    assertThatThrownBy(
            () ->
                reports.completeDraft(
                    project,
                    owner,
                    UUID.randomUUID(),
                    "报告",
                    md,
                    List.of(forged()),
                    List.of(),
                    true))
        .hasMessageContaining("授权");
    var draft =
        reports.completeDraft(
            project, owner, UUID.randomUUID(), "报告", md, List.of(forged()), List.of(version), true);
    sql("UPDATE business.documents SET state='DELETED' WHERE id='" + document + "'");
    var viewed = reports.version(draft.reportId(), owner, 1);
    assertThat(viewed.citations().getFirst().availability()).isEqualTo(Availability.DELETED);
    assertThat(viewed.citations().getFirst().excerpt()).isNull();
    assertThat(reports.copy(draft.reportId(), owner, 1, md).citations().getFirst().excerpt())
        .isNull();
    assertThatThrownBy(
            () ->
                reports.completeDraft(
                    project,
                    owner,
                    UUID.randomUUID(),
                    "报告",
                    md,
                    List.of(forged()),
                    List.of(version),
                    true))
        .hasMessageContaining("引用");
    assertThat(reports.save(draft.reportId(), owner, 1, "引用已移除").citations()).isEmpty();
  }
}
