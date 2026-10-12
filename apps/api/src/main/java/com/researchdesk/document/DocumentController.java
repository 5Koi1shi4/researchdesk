package com.researchdesk.document;

import com.researchdesk.identity.*;
import com.researchdesk.Contracts;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class DocumentController {
 private final DocumentService documents;private final SessionService sessions;private final com.researchdesk.task.TaskService tasks;
 public DocumentController(DocumentService documents,SessionService sessions,com.researchdesk.task.TaskService tasks){this.documents=documents;this.sessions=sessions;this.tasks=tasks;}
 @PostMapping(value="/documents",consumes="multipart/form-data")
 public ResponseEntity<Contracts.DocumentUpload> upload(@PathVariable UUID projectId,@RequestPart("file") MultipartFile file,HttpServletRequest request){
  AuthController.sameOrigin(request);var user=sessions.requireWrite(request).user();
  var accepted=documents.upload(projectId,user.id(),file);
  return ResponseEntity.status(accepted.duplicate()?200:201).header("Cache-Control","no-store").body(accepted.upload());
 }
 @GetMapping("/documents") public ResponseEntity<List<Contracts.Document>> list(@PathVariable UUID projectId,HttpServletRequest request){return ResponseEntity.ok().header("Cache-Control","no-store").body(documents.list(projectId,sessions.require(request).user().id()));}
 @GetMapping("/tasks/{taskId}") public ResponseEntity<Contracts.DocumentTask> task(@PathVariable UUID projectId,@PathVariable UUID taskId,HttpServletRequest request){return ResponseEntity.ok().header("Cache-Control","no-store").body(documents.task(projectId,sessions.require(request).user().id(),taskId));}
 @PostMapping("/tasks/{taskId}/retry") public ResponseEntity<Contracts.DocumentTask> retry(@PathVariable UUID projectId,@PathVariable UUID taskId,HttpServletRequest request){AuthController.sameOrigin(request);var user=sessions.requireWrite(request).user();tasks.retry(projectId,user.id(),taskId);return ResponseEntity.accepted().header("Cache-Control","no-store").body(documents.task(projectId,user.id(),taskId));}
}
