package com.researchdesk.report;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExportIT extends ReportVersionIT {
  @Test
  void exportIsFixedToHistoricalVersionPrivateAndDurable() throws Exception {
    var a = user();
    var b = user();
    String p = call("POST", "/api/projects", Map.of("name", "export"), a).body().get("id").asText();
    String id =
        call(
                "POST",
                "/api/projects/" + p + "/reports",
                Map.of(
                    "title",
                    "中文研究",
                    "markdown",
                    "# 中文研究\n\n"
                        + "固定版本一\n\n"
                        + "<img src=file:///etc/passwd>\n"
                        + "![外部](https://example.invalid/a.png)"),
                a)
            .body()
            .get("reportId")
            .asText();
    var accepted =
        call(
            "POST",
            "/api/reports/" + id + "/exports",
            Map.of("version", 1, "format", "MARKDOWN"),
            a);
    assertThat(accepted.status()).isEqualTo(202);
    String exportId = accepted.body().get("id").asText();
    String path = "/api/reports/" + id + "/exports/" + exportId;
    assertThat(call("GET", path, null, b).status()).isEqualTo(404);
    assertThat(call("GET", path + "/download", null, b).status()).isEqualTo(404);
    assertThat(
            call(
                    "POST",
                    "/api/reports/" + id + "/versions",
                    Map.of("baseVersion", 1, "markdown", "版本二"),
                    a)
                .status())
        .isEqualTo(201);
    String state = "";
    for (int i = 0; i < 120; i++) {
      state = call("GET", path, null, a).body().get("state").asText();
      if (state.equals("SUCCEEDED") || state.equals("FAILED")) break;
      Thread.sleep(500);
    }
    assertThat(state).isEqualTo("SUCCEEDED");
    var r =
        http.send(
            HttpRequest.newBuilder(URI.create(base + path + "/download"))
                .header("Cookie", a.cookie())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("固定版本一").doesNotContain("版本二");
    assertThat(r.headers().firstValue("Content-Disposition").orElseThrow()).contains("attachment");
  }

  String sql(String query) throws Exception {
    var process =
        new ProcessBuilder(
                "docker",
                "compose",
                "-p",
                "researchdesk-reports",
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

  String export(String id, String format, Session owner) throws Exception {
    var r =
        call(
            "POST",
            "/api/reports/" + id + "/exports",
            Map.of("version", 1, "format", format),
            owner);
    assertThat(r.status()).isEqualTo(202);
    return "/api/reports/" + id + "/exports/" + r.body().get("id").asText();
  }

  void complete(String path, Session owner) throws Exception {
    String state = "";
    for (int i = 0; i < 120; i++) {
      state = call("GET", path, null, owner).body().get("state").asText();
      if (state.equals("SUCCEEDED") || state.equals("FAILED")) break;
      Thread.sleep(500);
    }
    assertThat(state).isEqualTo("SUCCEEDED");
  }

  HttpResponse<byte[]> bytes(String path, Session owner) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(base + path + "/download"))
            .header("Cookie", owner.cookie())
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  @Test
  void deletedSourceRevokesEarlierArtifactAndNewExportRedactsItsExcerpt() throws Exception {
    var a = user();
    String project =
        call("POST", "/api/projects", Map.of("name", "deletion"), a).body().get("id").asText();
    String id =
        call(
                "POST",
                "/api/projects/" + project + "/reports",
                Map.of("title", "来源删除", "markdown", "draft"),
                a)
            .body()
            .get("reportId")
            .asText();
    UUID document = UUID.randomUUID(), version = UUID.randomUUID(), chunk = UUID.randomUUID();
    var citation =
        new com.researchdesk.Contracts.Citation(
            chunk.toString(),
            version.toString(),
            "资料",
            null,
            1,
            "SECRET_SOURCE_EXCERPT",
            com.researchdesk.Contracts.Availability.AVAILABLE);
    String citations = JSON.writeValueAsString(List.of(citation));
    sql(
        "INSERT INTO business.documents(id,project_id,title,content_sha256,state) VALUES('"
            + document
            + "','"
            + project
            + "','资料','"
            + "b".repeat(64)
            + "','READY'); INSERT INTO"
            + " business.document_versions(id,document_id,project_id,object_key,media_type,size_bytes,parser_version)"
            + " VALUES('"
            + version
            + "','"
            + document
            + "','"
            + project
            + "','export-test/"
            + version
            + "','text/markdown',10,'sections-v1'); UPDATE business.report_versions SET"
            + " markdown='正文 [[cite:"
            + chunk
            + "]]',citations='"
            + citations
            + "'::jsonb WHERE report_id='"
            + id
            + "' AND version=1;");
    String first = export(id, "MARKDOWN", a);
    complete(first, a);
    assertThat(new String(bytes(first, a).body(), java.nio.charset.StandardCharsets.UTF_8))
        .contains("SECRET_SOURCE_EXCERPT");
    sql("UPDATE business.documents SET state='DELETED' WHERE id='" + document + "'");
    assertThat(bytes(first, a).statusCode()).isEqualTo(409);
    String second = export(id, "MARKDOWN", a);
    complete(second, a);
    var regenerated = bytes(second, a);
    assertThat(regenerated.statusCode()).isEqualTo(200);
    assertThat(new String(regenerated.body(), java.nio.charset.StandardCharsets.UTF_8))
        .doesNotContain("SECRET_SOURCE_EXCERPT");
    assertThat(
            call("GET", "/api/reports/" + id + "/versions/1", null, a)
                .body()
                .get("citations")
                .get(0)
                .get("availability")
                .asText())
        .isEqualTo("DELETED");
  }

  @Test
  void expiredLeaseRecoversAndRealPdfRetainsChinese() throws Exception {
    var a = user();
    String project =
        call("POST", "/api/projects", Map.of("name", "recovery"), a).body().get("id").asText();
    String id =
        call(
                "POST",
                "/api/projects/" + project + "/reports",
                Map.of("title", "中文报告", "markdown", "# 中文报告\n\n" + "多页研究内容与来源证据。\n\n".repeat(200)),
                a)
            .body()
            .get("reportId")
            .asText();
    String path = export(id, "PDF", a);
    String exportId = path.substring(path.lastIndexOf('/') + 1);
    sql(
        "UPDATE business.report_exports SET"
            + " state='RUNNING',attempts=1,fence=1,lease_until=now()-interval '1 second' WHERE id='"
            + exportId
            + "'");
    complete(path, a);
    var downloaded = bytes(path, a);
    assertThat(downloaded.statusCode()).isEqualTo(200);
    assertThat(new String(downloaded.body(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
        .isEqualTo("%PDF");
    try (var pdf = org.apache.pdfbox.Loader.loadPDF(downloaded.body())) {
      assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
      assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf))
          .contains("中文报告", "研究内容");
    }
    assertThat(
            sql(
                "SELECT attempts||':'||fence FROM business.report_exports WHERE id='"
                    + exportId
                    + "'"))
        .isEqualTo("2:2");
  }
}
