package com.researchdesk.research;
import com.researchdesk.Contracts.*;
import java.util.*;
/** Reject variant confusion before any provider event is persisted. */
public final class RunEventValidator {
 private RunEventValidator() {}
 public static void validate(EventType type,Map<String,Object> payload){
  if(type==null||payload==null)throw new IllegalArgumentException("Missing event");
  String key=switch(type){case STARTED->"kind";case DELTA->"text";case CITATION->"citation";case COMPLETED,CANCELLED->"state";case FAILED->"error";};
  if(!payload.keySet().equals(Set.of(key)))throw new IllegalArgumentException("Invalid event payload fields");
  Object value=payload.get(key);
  switch(type){
   case STARTED->{if(!Set.of("ANSWER","REPORT").contains(value))throw new IllegalArgumentException("Invalid kind");}
   case DELTA->{if(!(value instanceof String s)||s.isEmpty()||s.length()>32768)throw new IllegalArgumentException("Invalid delta");}
   case COMPLETED,CANCELLED->{if(!type.name().equals(value))throw new IllegalArgumentException("Invalid terminal state");}
   case FAILED->{if(!(value instanceof Map<?,?> m)||!m.keySet().equals(Set.of("code","message","requestId"))||m.values().stream().anyMatch(v->!(v instanceof String s)||s.isBlank()))throw new IllegalArgumentException("Invalid error");}
   case CITATION->{if(!(value instanceof Map<?,?> m)||!m.keySet().equals(Set.of("chunkId","documentVersionId","title","page","paragraph","excerpt","availability")))throw new IllegalArgumentException("Invalid citation");
    for(String field:List.of("chunkId","documentVersionId","title"))if(!(m.get(field) instanceof String s)||s.isBlank())throw new IllegalArgumentException("Invalid citation field");
    UUID.fromString((String)m.get("chunkId"));UUID.fromString((String)m.get("documentVersionId"));
    for(String field:List.of("page","paragraph"))if(m.get(field)!=null&&(!(m.get(field) instanceof Integer n)||n<1))throw new IllegalArgumentException("Invalid location");
    if(m.get("excerpt")!=null&&!(m.get("excerpt") instanceof String))throw new IllegalArgumentException("Invalid excerpt");
    if(!"AVAILABLE".equals(m.get("availability")))throw new IllegalArgumentException("Invalid availability");
   }
  }
 }
}
