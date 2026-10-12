package com.researchdesk.report;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ReportVersionIT {
  static final ObjectMapper JSON = new ObjectMapper();
  final HttpClient http = HttpClient.newHttpClient();
  final String base = com.researchdesk.ComposeFixture.baseUrl();

  record Session(String cookie, String csrf) {}

  record Result(int status, JsonNode body) {}

  Result call(String method, String path, Object body, Session s) throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
    if (s != null) b.header("Cookie", s.cookie()).header("X-CSRF-Token", s.csrf());
    b.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Result(r.statusCode(), JSON.readTree(r.body()));
  }

  private final List<String> fixtureEmails = new ArrayList<>();

  @org.junit.jupiter.api.AfterEach
  void cleanOwnedUsers() throws Exception {
    for (String email : fixtureEmails) {
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
                  "-c",
                  "DELETE FROM business.users WHERE email=\u0027"
                      + email
                      + "\u0027; DELETE FROM business.auth_throttles WHERE key=\u0027email:"
                      + email
                      + "\u0027;")
              .redirectErrorStream(true)
              .start();
      String output = new String(process.getInputStream().readAllBytes());
      assertThat(process.waitFor()).as(output).isZero();
    }
  }

  Session user() throws Exception {
    String email = "report-" + UUID.randomUUID() + "@researchdesk.test";
    fixtureEmails.add(email);
    var b =
        HttpRequest.newBuilder(URI.create(base + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    JSON.writeValueAsString(
                        Map.of("email", email, "password", "CorrectHorseBattery9!"))));
    var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(201);
    return new Session(
        r.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0],
        JSON.readTree(r.body()).get("csrfToken").asText());
  }

  @Test
  void concurrentWritesAllowOnlyOneWinner() throws Exception {
    var a = user();
    String p = call("POST", "/api/projects", Map.of("name", "race"), a).body().get("id").asText();
    String id =
        call(
                "POST",
                "/api/projects/" + p + "/reports",
                Map.of("title", "race", "markdown", "original"),
                a)
            .body()
            .get("reportId")
            .asText();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var jobs = new ArrayList<java.util.concurrent.Future<Integer>>();
      for (int n = 0; n < 2; n++) {
        final int i = n;
        jobs.add(
            pool.submit(
                () -> {
                  gate.await();
                  return call(
                          "POST",
                          "/api/reports/" + id + "/versions",
                          Map.of("baseVersion", 1, "markdown", "writer" + i),
                          a)
                      .status();
                }));
      }
      gate.countDown();
      assertThat(List.of(jobs.get(0).get(), jobs.get(1).get())).containsExactlyInAnyOrder(201, 409);
      assertThat(call("GET", "/api/reports/" + id + "/versions", null, a).body()).hasSize(2);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void immutableHistoryConflictCopyAndOwnerScope() throws Exception {
    var a = user();
    var b = user();
    String p =
        call("POST", "/api/projects", Map.of("name", "reports"), a).body().get("id").asText();
    var created =
        call("POST", "/api/projects/" + p + "/reports", Map.of("title", "研究", "markdown", "v1"), a);
    assertThat(created.status()).isEqualTo(201);
    String id = created.body().get("reportId").asText();
    assertThat(
            call(
                    "POST",
                    "/api/reports/" + id + "/versions",
                    Map.of("baseVersion", 1, "markdown", "v2"),
                    a)
                .status())
        .isEqualTo(201);
    var conflict =
        call(
            "POST",
            "/api/reports/" + id + "/versions",
            Map.of("baseVersion", 1, "markdown", "other"),
            a);
    assertThat(conflict.status()).isEqualTo(409);
    assertThat(conflict.body().get("code").asText()).isEqualTo("VERSION_CONFLICT");
    assertThat(
            call("GET", "/api/reports/" + id + "/versions/1", null, a)
                .body()
                .get("markdown")
                .asText())
        .isEqualTo("v1");
    assertThat(call("GET", "/api/reports/" + id, null, b).status()).isEqualTo(404);
    assertThat(
            call(
                    "POST",
                    "/api/reports/" + id + "/copies",
                    Map.of("version", 1, "markdown", "other"),
                    a)
                .status())
        .isEqualTo(201);
    assertThat(
            call(
                    "POST",
                    "/api/reports/" + id + "/versions",
                    Map.of("baseVersion", 2, "markdown", "[[cite:" + UUID.randomUUID() + "]]"),
                    a)
                .status())
        .isEqualTo(400);
  }
}
