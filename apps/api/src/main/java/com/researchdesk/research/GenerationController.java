package com.researchdesk.research;
import com.researchdesk.Contracts.GenerationRequest; import com.researchdesk.identity.*;
import jakarta.servlet.http.HttpServletRequest; import java.util.*;
import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*;
@RestController
public class GenerationController {
 private final GenerationService runs;private final GenerationWorker worker;private final SessionService sessions;
 public GenerationController(GenerationService runs,GenerationWorker worker,SessionService sessions){this.runs=runs;this.worker=worker;this.sessions=sessions;}
 @PostMapping("/api/runs") public ResponseEntity<GenerationService.Snapshot> create(@RequestBody GenerationRequest input,HttpServletRequest request){AuthController.sameOrigin(request);var owner=sessions.requireWrite(request).user().id();return ResponseEntity.status(202).header("Cache-Control","no-store").body(runs.create(owner,input));}
 @GetMapping("/api/runs/{id}") public ResponseEntity<GenerationService.Snapshot> get(@PathVariable UUID id,HttpServletRequest request){return ResponseEntity.ok().header("Cache-Control","no-store").body(runs.get(sessions.require(request).user().id(),id));}
 @PostMapping("/api/runs/{id}/cancel") public ResponseEntity<GenerationService.Snapshot> cancel(@PathVariable UUID id,HttpServletRequest request){AuthController.sameOrigin(request);var result=runs.cancel(sessions.requireWrite(request).user().id(),id);worker.cancel(id);return ResponseEntity.ok().header("Cache-Control","no-store").body(result);}
 @GetMapping("/api/projects/{projectId}/conversations") public List<GenerationService.Conversation> conversations(@PathVariable UUID projectId,HttpServletRequest request){return runs.conversations(sessions.require(request).user().id(),projectId);}
 @GetMapping("/api/conversations/{id}") public GenerationService.ConversationDetail conversation(@PathVariable UUID id,HttpServletRequest request){return runs.conversation(sessions.require(request).user().id(),id);}
}
