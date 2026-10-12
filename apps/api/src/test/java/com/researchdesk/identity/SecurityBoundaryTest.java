package com.researchdesk.identity;

import com.researchdesk.project.ProjectController;
import com.researchdesk.project.ProjectService;
import com.researchdesk.task.InternalTaskController;
import com.researchdesk.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers={ProjectController.class, InternalTaskController.class, AuthController.class}, properties={
    "researchdesk.auth.session-seconds=86400", "researchdesk.auth.cookie-secure=true", "INTERNAL_SERVICE_TOKEN=test-internal-token-at-least-32-characters"})
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@Import(SessionService.class)
class SecurityBoundaryTest {
    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate database;
    @MockitoBean ProjectService projects;
    @MockitoBean TaskService tasks;

    @Test void taskCallbackLogLinksRequestAndAttemptWithoutObjectContent(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var task=java.util.UUID.randomUUID(); var attempt=java.util.UUID.randomUUID();
        String requestId=java.util.UUID.randomUUID().toString();
        var body=new TaskService.Attempt(attempt,7);
        var canonical=new com.researchdesk.Contracts.InternalTask(task.toString(),attempt.toString(),7,
            java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),"private-object-marker","parser-v1");
        org.mockito.Mockito.when(tasks.claim(task,body)).thenReturn(new TaskService.Claim(canonical,"text/markdown",30));
        mvc.perform(post("/internal/tasks/"+task+"/claim").header("X-Request-Id",requestId)
            .header("Authorization","Bearer test-internal-token-at-least-32-characters").contentType("application/json")
            .content("{\"attemptId\":\""+attempt+"\",\"fence\":7}"))
            .andExpect(status().isOk());
        assertThat(output.getOut()).contains("TASK_CALLBACK requestId="+requestId+" taskId="+task+" attemptId="+attempt+" fence=7 action=claim")
            .doesNotContain("private-object-marker","test-internal-token-at-least-32-characters");
    }
    @Test void productionCookieIsSecureAndHttpOnly() throws Exception {
        org.mockito.Mockito.when(database.queryForObject(org.mockito.ArgumentMatchers.startsWith("INSERT INTO business.auth_throttles"),org.mockito.ArgumentMatchers.eq(Integer.class),org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        mvc.perform(post("/api/auth/register").contentType("application/json")
            .content("{\"email\":\"fixture@example.test\",\"password\":\"CorrectHorseBattery9!\"}"))
            .andExpect(status().isCreated()).andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("Secure"),org.hamcrest.Matchers.containsString("HttpOnly"),org.hamcrest.Matchers.containsString("SameSite=Lax"))));
    }
    @Test void auditLogContainsOnlyRequestMetadata(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var result=mvc.perform(get("/api/projects?secret=private-query-marker").header("Authorization","Bearer private-auth-marker")
            .header("X-Request-Id","private-header-marker").content("private-body-marker")).andExpect(status().isUnauthorized()).andReturn();
        assertThat(output.getOut()).contains("HTTP_REQUEST requestId="+result.getResponse().getHeader("X-Request-Id"),"method=GET status=401 durationMs=")
            .doesNotContain("private-query-marker","private-auth-marker","private-body-marker","private-header-marker");
    }
    @Test void validatedEdgeRequestIdSurvivesInErrorResponse() throws Exception {
        String id="a123456789abcdef0123456789abcdef";
        mvc.perform(get("/api/projects").header("X-Request-Id",id)).andExpect(status().isUnauthorized())
            .andExpect(header().string("X-Request-Id",id)).andExpect(jsonPath("$.requestId").value(id));
    }
    @Test void nullProjectJsonReturnsStructuredBadRequest() throws Exception {
        mvc.perform(post("/api/projects").contentType("application/json").content("null"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }
    @Test void errorResponseAndHeaderShareServerGeneratedRequestId() throws Exception {
        var result=mvc.perform(get("/api/projects").header("X-Request-Id", "untrusted-id"))
            .andExpect(status().isUnauthorized()).andExpect(header().exists("X-Request-Id"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        String requestId=result.getResponse().getHeader("X-Request-Id");
        assertThat(requestId).matches("[0-9a-f-]{36}").isNotEqualTo("untrusted-id");
        assertThat(result.getResponse().getContentAsString()).contains(requestId);
    }
    @Test void internalTaskBoundaryRejectsMissingAndWrongToken() throws Exception {
        String body="{\"attemptId\":\"00000000-0000-0000-0000-000000000001\",\"fence\":1}";
        for(String token:new String[]{"", "Bearer attacker"})
            mvc.perform(post("/internal/tasks/00000000-0000-0000-0000-000000000002/claim")
                .header("Authorization",token).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SERVICE_AUTH_REQUIRED"));
    }
    @Test void crossOriginWriteIsRejectedBeforeAuthentication() throws Exception {
        mvc.perform(post("/api/projects").header("Host","research.test").header("Origin","https://evil.test")
            .contentType("application/json").content("{\"name\":\"private\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_REJECTED"));
    }
}
