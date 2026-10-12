package com.researchdesk.research;
import com.fasterxml.jackson.databind.ObjectMapper;import org.junit.jupiter.api.Test;import static org.assertj.core.api.Assertions.*;
class InternalGenerationEventTest {
 private final ObjectMapper json=new ObjectMapper();
 private final String terminal="{\"runId\":\"r\",\"seq\":3,\"type\":\"completed\",\"payload\":{\"state\":\"COMPLETED\"},\"usage\":{\"provider\":\"demo\",\"model\":null,\"mode\":\"DEMO\",\"inputTokens\":null,\"outputTokens\":null,\"totalTokens\":null,\"durationMs\":4}}";
 @Test void stripsValidatedInternalUsageBeforePublicSerialization()throws Exception{
  var parsed=InternalGenerationEvent.parse(json,terminal);assertThat(parsed.usage().get("durationMs")).isEqualTo(4);
  var publicEvent=json.readTree(json.writeValueAsString(parsed.event()));assertThat(publicEvent.has("usage")).isFalse();assertThat(publicEvent.size()).isEqualTo(4);RunEventValidator.validate(parsed.event().type(),parsed.event().payload());
 }
 @Test void rejectsInvalidUsageAndNonterminalMetadata(){
  for(String changed:new String[]{terminal.replace("\"durationMs\":4","\"durationMs\":-1"),terminal.replace("\"durationMs\":4","\"durationMs\":\"4\""),terminal.replace("\"durationMs\":4","\"secret\":\"key\""),terminal.replace("completed","delta").replace("\"state\":\"COMPLETED\"","\"text\":\"hi\"")})assertThatThrownBy(()->InternalGenerationEvent.parse(json,changed)).isInstanceOf(IllegalArgumentException.class);
 }
}
