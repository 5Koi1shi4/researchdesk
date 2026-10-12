package com.researchdesk.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit Compose acceptance: real HTTP, real PostgreSQL, no host database port. */
class ProjectOwnershipIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String base = com.researchdesk.ComposeFixture.baseUrl();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> fixtureEmails = new ArrayList<>();
    record Session(String cookie, String csrf, String email) {}
    record Result(int status, JsonNode body, HttpHeaders headers) {}
    String fixtureSql(String sql) throws Exception {
        var process = new ProcessBuilder("docker", "compose", "-p", com.researchdesk.ComposeFixture.project(), "-f", java.nio.file.Path.of(System.getProperty("user.dir"), "../../infra/compose.yaml").normalize().toString(), "exec", "-T", "postgres", "psql", "-U", "researchdesk", "-d", "researchdesk", "-v", "ON_ERROR_STOP=1", "-At", "-c", sql).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as("fixture SQL: " + output).isZero(); return output.strip();
    }

    Result request(String method, String path, String body, Session session, boolean csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json");
        if (session != null) builder.header("Cookie", session.cookie());
        if (csrf && session != null) builder.header("X-CSRF-Token", session.csrf());
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var result = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(result.statusCode(), result.body().isBlank() ? JSON.nullNode() : JSON.readTree(result.body()), result.headers());
    }
    Session register() throws Exception {
        String email = "it-" + UUID.randomUUID() + "@researchdesk.test";

        var result = request("POST", "/api/auth/register", "{\"email\":\"" + email + "\",\"password\":\"CorrectHorseBattery9!\"}", null, false);
        assertThat(result.status()).as("registration on available Compose API").isEqualTo(201);
        fixtureEmails.add(email);
        String cookie = result.headers().firstValue("set-cookie").orElseThrow();
        assertThat(cookie).contains("HttpOnly", "SameSite=Lax", "Path=/");
        return new Session(cookie.split(";", 2)[0], result.body().get("csrfToken").asText(), email);
    }
    @BeforeEach void requireLiveDependencies() throws Exception {
        assertThat(request("GET", "/api/ready", null, null, false).status()).as("Start researchdesk Compose with PostgreSQL before acceptance").isEqualTo(200);
    }
    @AfterEach void cleanOnlyOwnedFixtures() throws Exception {
        // Docker is only a fixture administrator, never the API under test.
        for (String email : fixtureEmails) {
            fixtureSql("DELETE FROM business.users WHERE email = '" + email + "'; DELETE FROM business.auth_throttles WHERE key = 'email:" + email + "';");
        }
    }
    @Test void foreignAndMissingProjectsHaveTheSame404AndListsArePrivate() throws Exception {
        var a = register(); var b = register();
        var created = request("POST", "/api/projects", "{\"name\":\"私有研究\"}", a, true);
        assertThat(created.status()).isEqualTo(201);
        String id = created.body().get("id").asText();
        assertThat(request("GET", "/api/projects/" + id, null, a, false).status()).isEqualTo(200);
        var foreign = request("GET", "/api/projects/" + id, null, b, false);
        var missing = request("GET", "/api/projects/" + UUID.randomUUID(), null, b, false);
        assertThat(foreign.status()).isEqualTo(404); assertThat(missing.status()).isEqualTo(404);
        assertThat(foreign.body().get("code")).isEqualTo(missing.body().get("code"));
        assertThat(foreign.body().get("message")).isEqualTo(missing.body().get("message"));
        assertThat(foreign.body().fieldNames()).toIterable().containsExactlyInAnyOrder("code", "message", "requestId");
        assertThat(request("GET", "/api/projects", null, b, false).body()).isEmpty();
        assertThat(request("GET", "/api/projects", null, a, false).body()).hasSize(1);
    }
    @Test void writesRequireCsrfAndLogoutRevokesTheServerSession() throws Exception {
        var session = register();
        assertThat(request("POST", "/api/projects", "{\"name\":\"Forbidden\"}", session, false).status()).isEqualTo(403);
        assertThat(request("POST", "/api/auth/logout", null, session, false).status()).isEqualTo(403);
        assertThat(request("POST", "/api/auth/logout", null, session, true).status()).isEqualTo(204);
        assertThat(request("GET", "/api/projects", null, session, false).status()).isEqualTo(401);
        assertThat(request("GET", "/api/auth/session", null, session, false).status()).isEqualTo(401);
    }
    @Test void loginRotatesCookieAndCsrfAndRejectsTheOldSession() throws Exception {
        var old = register();
        var result = request("POST", "/api/auth/login", "{\"email\":\"" + old.email() + "\",\"password\":\"CorrectHorseBattery9!\"}", old, true);
        assertThat(result.status()).isEqualTo(200);
        var fresh = new Session(result.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0], result.body().get("csrfToken").asText(), old.email());
        assertThat(fresh.cookie()).isNotEqualTo(old.cookie()); assertThat(fresh.csrf()).isNotEqualTo(old.csrf());
        assertThat(request("GET", "/api/projects", null, old, false).status()).isEqualTo(401);
        assertThat(request("POST", "/api/projects", "{\"name\":\"Rotated\"}", new Session(fresh.cookie(), old.csrf(), old.email()), true).status()).isEqualTo(403);
        assertThat(request("GET", "/api/auth/session", null, fresh, false).body().get("user").get("email").asText()).isEqualTo(old.email());
    }
    @Test void validatesCredentialsAndThrottlesRepeatedFailedLogins() throws Exception {
        assertThat(request("POST", "/api/auth/register", "{\"email\":\"invalid\",\"password\":\"short\"}", null, false).status()).isEqualTo(400);
        var session = register();
        assertThat(request("POST", "/api/auth/register", "{\"email\":\"" + session.email() + "\",\"password\":\"CorrectHorseBattery9!\"}", null, false).status()).isEqualTo(409);
        String body = "{\"email\":\"" + session.email() + "\",\"password\":\"IncorrectPassword!\"}";
        for (int i = 0; i < 5; i++) assertThat(request("POST", "/api/auth/login", body, null, false).status()).isEqualTo(401);
        assertThat(request("POST", "/api/auth/login", body, null, false).status()).isEqualTo(429);
        var attack = HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("Origin", "https://evil.example").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertThat(http.send(attack, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
    @Test void expiredSessionsAreRejectedAndSecretsAreHashedAtRest() throws Exception {
        var session=register();
        String stored=fixtureSql("SELECT u.password_hash || '|' || s.token_hash FROM business.users u JOIN business.sessions s ON s.user_id=u.id WHERE u.email='"+session.email()+"'");
        assertThat(stored).startsWith("$2a$12$").doesNotContain("CorrectHorseBattery9!").doesNotContain(session.cookie().split("=",2)[1]);
        fixtureSql("UPDATE business.sessions SET expires_at=now()-interval '1 second' WHERE user_id=(SELECT id FROM business.users WHERE email='"+session.email()+"')");
        assertThat(request("GET", "/api/projects", null, session, false).status()).isEqualTo(401);
    }
    @Test void runtimeDatabaseRolesCannotCrossTheBusinessRetrievalBoundary() throws Exception {
        assertThat(fixtureSql("SELECT rolname || ':' || rolsuper FROM pg_roles WHERE rolname IN ('researchdesk_business','researchdesk_retrieval') ORDER BY rolname")).isEqualTo("researchdesk_business:false\nresearchdesk_retrieval:false");
        assertThat(fixtureSql("SELECT has_schema_privilege('researchdesk_retrieval','business','USAGE') || ':' || has_table_privilege('researchdesk_retrieval','business.users','SELECT') || ':' || has_schema_privilege('researchdesk_business','retrieval','USAGE')")).isEqualTo("false:false:false");
    }
}
