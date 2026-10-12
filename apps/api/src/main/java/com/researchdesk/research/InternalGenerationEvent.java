package com.researchdesk.research;
import com.fasterxml.jackson.databind.ObjectMapper;import com.fasterxml.jackson.core.type.TypeReference;
import com.researchdesk.Contracts.RunEvent;import com.researchdesk.Contracts.EventType;import java.util.*;
/** Private Python transport extension. Only event() may enter public persistence/SSE. */
record InternalGenerationEvent(RunEvent event,Map<String,Object> usage) {
 static InternalGenerationEvent parse(ObjectMapper json,String line)throws java.io.IOException {
  var tree=json.readTree(line);if(tree==null||!tree.isObject())throw new IllegalArgumentException("Invalid internal event");
  var fields=new HashSet<String>();tree.fieldNames().forEachRemaining(fields::add);
  if(!Set.of("runId","seq","type","payload","usage").containsAll(fields)||!fields.containsAll(Set.of("runId","seq","type","payload")))throw new IllegalArgumentException("Invalid event fields");
  var raw=((com.fasterxml.jackson.databind.node.ObjectNode)tree).remove("usage");
  var event=json.treeToValue(tree,RunEvent.class);RunEventValidator.validate(event.type(),event.payload());
  Map<String,Object> usage=null;
  if(raw!=null){
   if(!Set.of(EventType.COMPLETED,EventType.FAILED,EventType.CANCELLED).contains(event.type())||!raw.isObject())throw new IllegalArgumentException("Invalid usage envelope");
   usage=json.convertValue(raw,new TypeReference<Map<String,Object>>(){});
   if(!Set.of("provider","model","mode","inputTokens","outputTokens","totalTokens","durationMs","retrievalMs","ttftMs").containsAll(usage.keySet())||!usage.keySet().containsAll(Set.of("provider","model","mode","inputTokens","outputTokens","totalTokens")))throw new IllegalArgumentException("Invalid usage fields");
   if(!Set.of("demo","openai-compatible").contains(usage.get("provider"))||!Set.of("DEMO","REAL").contains(usage.get("mode")))throw new IllegalArgumentException("Invalid usage source");
   if(usage.get("model")!=null&&UsageService.label(usage.get("model"),200)==null)throw new IllegalArgumentException("Invalid usage model");
   for(String key:List.of("inputTokens","outputTokens","totalTokens","durationMs","retrievalMs","ttftMs"))if(usage.get(key)!=null&&UsageService.token(usage.get(key))==null)throw new IllegalArgumentException("Invalid usage number");
  }
  return new InternalGenerationEvent(event,usage);
 }
}
