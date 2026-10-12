package com.researchdesk.research;
import com.researchdesk.identity.*;import jakarta.servlet.http.HttpServletRequest;import java.util.UUID;
import org.junit.jupiter.api.Test;import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class RunStreamErrorTest {
 @Test void malformedSseCursorHasJsonErrorRegardlessOfAccept()throws Exception{
  var sessions=new SessionService(null,0){@Override public Principal require(HttpServletRequest r){return new Principal(new User(UUID.randomUUID(),"test@local"),"csrf","hash");}};
  var mvc=MockMvcBuilders.standaloneSetup(new RunStreamController(null,sessions)).setControllerAdvice(new ApiExceptionHandler()).build();
  mvc.perform(get("/api/runs/"+UUID.randomUUID()+"/events").header("Accept","text/event-stream").header("Last-Event-ID","invalid")).andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/json")).andExpect(jsonPath("$.code").value("INVALID_EVENT_CURSOR"));
 }
}
