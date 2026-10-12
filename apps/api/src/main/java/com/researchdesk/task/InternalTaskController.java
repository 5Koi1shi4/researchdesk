package com.researchdesk.task;

import com.researchdesk.identity.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/tasks")
public class InternalTaskController {
 private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(InternalTaskController.class);
 private final TaskService tasks;private final byte[] token;
 public InternalTaskController(TaskService tasks,@Value("${INTERNAL_SERVICE_TOKEN:}") String token){this.tasks=tasks;this.token=("Bearer "+token).getBytes(StandardCharsets.UTF_8);if(token.length()<32)throw new IllegalArgumentException("INTERNAL_SERVICE_TOKEN must contain at least 32 characters");}
 private void authorize(String authorization){if(authorization==null||!MessageDigest.isEqual(token,authorization.getBytes(StandardCharsets.UTF_8)))throw new ApiFailure(401,"SERVICE_AUTH_REQUIRED","服务身份验证失败。");}
 @PostMapping("/{id}/claim") public TaskService.Claim claim(@PathVariable UUID id,@RequestHeader(value="Authorization",required=false) String auth,@RequestBody TaskService.Attempt a){authorize(auth);var result=tasks.claim(id,a);audit(id,a,"claim");return result;}
 @PostMapping("/{id}/heartbeat") public void heartbeat(@PathVariable UUID id,@RequestHeader(value="Authorization",required=false) String auth,@RequestBody TaskService.Attempt a){authorize(auth);tasks.heartbeat(id,a);}
 @PostMapping("/{id}/complete") public void complete(@PathVariable UUID id,@RequestHeader(value="Authorization",required=false) String auth,@RequestBody TaskService.Attempt a){authorize(auth);tasks.complete(id,a);audit(id,a,"complete");}
 @PostMapping("/{id}/fail") public void fail(@PathVariable UUID id,@RequestHeader(value="Authorization",required=false) String auth,@RequestBody TaskService.Failure f){authorize(auth);if(f.code()==null||f.code().length()>80)throw new ApiFailure(400,"INVALID_REQUEST","无效的故障代码。");tasks.fail(id,f);audit(id,new TaskService.Attempt(f.attemptId(),f.fence()),"fail");}
 private void audit(UUID id,TaskService.Attempt attempt,String action){
  LOG.info("TASK_CALLBACK requestId={} taskId={} attemptId={} fence={} action={}",org.slf4j.MDC.get("requestId"),id,attempt.attemptId(),attempt.fence(),action);
 }
}
