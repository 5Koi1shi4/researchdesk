package com.researchdesk.research;
import java.util.*;import org.junit.jupiter.api.Test;import static org.assertj.core.api.Assertions.*;
/** Uses the explicitly selected isolated Compose API/database; never changes service availability. */
class UsageIT extends RunRecoveryIT {
 @Test void cancelledUsageIsUnknownAndReplayDoesNotDoubleCount()throws Exception{
  String id=run("RUNNING",0);assertThat(request("POST","/api/runs/"+id+"/cancel",null).statusCode()).isEqualTo(200);
  assertThat(request("POST","/api/runs/"+id+"/cancel",null).statusCode()).isEqualTo(200);
  var response=request("GET","/api/projects/"+project+"/usage",null);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
  var rows=json.readTree(response.body());assertThat(rows.size()).isEqualTo(1);assertThat(rows.get(0).get("runId").asText()).isEqualTo(id);assertThat(rows.get(0).get("inputTokens").isNull()).isTrue();assertThat(rows.get(0).get("estimatedCost").isNull()).isTrue();assertThat(rows.get(0).get("state").asText()).isEqualTo("CANCELLED");
  assertThat(sql("SELECT count(*) FROM business.usage_records WHERE run_id='"+id+"'")).isEqualTo("1");assertThat(response.body()).doesNotContain("question","prompt","text");
 }
 @Test void foreignUsageAndMissingProjectHaveSameNotFoundResponse()throws Exception{
  String foreign=project;cookie=null;csrf=null;String email="usage-"+UUID.randomUUID()+"@test.local";users.add(email);
  var registration=request("POST","/api/auth/register","{\"email\":\""+email+"\",\"password\":\"PasswordTest123!\"}");cookie=registration.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];
  var denied=request("GET","/api/projects/"+foreign+"/usage",null);var missing=request("GET","/api/projects/"+UUID.randomUUID()+"/usage",null);
  assertThat(denied.statusCode()).isEqualTo(404);assertThat(missing.statusCode()).isEqualTo(404);assertThat(json.readTree(denied.body()).get("code")).isEqualTo(json.readTree(missing.body()).get("code"));
 }
 @Test void actualNoEvidenceCompletionPersistsDemoUnknownUsage()throws Exception{
  var response=request("POST","/api/runs",json.writeValueAsString(Map.of("kind","ANSWER","projectId",project,"documentVersionIds",List.of(),"prompt","private question")));assertThat(response.statusCode()).isEqualTo(202);String id=json.readTree(response.body()).get("runId").asText();
  long end=System.nanoTime()+15_000_000_000L;
  while(System.nanoTime()<end){var rows=json.readTree(request("GET","/api/projects/"+project+"/usage",null).body());if(rows.size()>0){var row=rows.get(0);assertThat(row.get("runId").asText()).isEqualTo(id);assertThat(row.get("state").asText()).isEqualTo("COMPLETED");assertThat(row.get("mode").asText()).isEqualTo("DEMO");assertThat(row.get("totalTokens").isNull()).isTrue();assertThat(row.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);return;}Thread.sleep(100);}throw new AssertionError("No terminal usage record");
 }
}
