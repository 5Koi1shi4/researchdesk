package com.researchdesk.research;
import com.fasterxml.jackson.databind.*;
import java.net.*; import java.net.http.*; import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class GenerationScopeIT {
 @Test void createsDurableRunAndRejectsForeignConversation() throws Exception {
  var h=HttpClient.newHttpClient(); var j=new ObjectMapper(); String base=com.researchdesk.ComposeFixture.baseUrl();
  var reg=h.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/register")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"gen-"+UUID.randomUUID()+"@test.local\",\"password\":\"PasswordTest123!\"}")).build(),HttpResponse.BodyHandlers.ofString());
  assertThat(reg.statusCode()).isEqualTo(201); String cookie=reg.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0], csrf=j.readTree(reg.body()).get("csrfToken").asText();
  var project=h.send(HttpRequest.newBuilder(URI.create(base+"/api/projects")).header("Content-Type","application/json").header("Cookie",cookie).header("X-CSRF-Token",csrf).POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"generation test\"}")).build(),HttpResponse.BodyHandlers.ofString());
  String pid=j.readTree(project.body()).get("id").asText();
  var run=h.send(HttpRequest.newBuilder(URI.create(base+"/api/runs")).header("Content-Type","application/json").header("Cookie",cookie).header("X-CSRF-Token",csrf).POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"ANSWER\",\"projectId\":\""+pid+"\",\"documentVersionIds\":[],\"prompt\":\"What is supported?\"}")).build(),HttpResponse.BodyHandlers.ofString());
  assertThat(run.statusCode()).isEqualTo(202); var body=j.readTree(run.body()); assertThat(body.path("runId").asText()).isNotBlank(); assertThat(body.path("mode").asText()).isEqualTo("DEMO");
  var invalid=h.send(HttpRequest.newBuilder(URI.create(base+"/api/runs")).header("Content-Type","application/json").header("Cookie",cookie).header("X-CSRF-Token",csrf).POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"ANSWER\",\"projectId\":\""+pid+"\",\"conversationId\":\""+UUID.randomUUID()+"\",\"documentVersionIds\":[],\"prompt\":\"x\"}")).build(),HttpResponse.BodyHandlers.ofString()); assertThat(invalid.statusCode()).isEqualTo(404);
 }
}
