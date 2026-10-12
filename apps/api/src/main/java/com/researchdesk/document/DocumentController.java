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
 private final DocumentService documents;private final SessionService sessions;
 public DocumentController(DocumentService documents,SessionService sessions){this.documents=documents;this.sessions=sessions;}
 @PostMapping(value="/documents",consumes="multipart/form-data")
 public ResponseEntity<Contracts.DocumentUpload> upload(@PathVariable UUID projectId,@RequestPart("file") MultipartFile file,HttpServletRequest request){
  AuthController.sameOrigin(request);var user=sessions.requireWrite(request).user();
  var accepted=documents.upload(projectId,user.id(),file);
  return ResponseEntity.status(accepted.duplicate()?200:201).header("Cache-Control","no-store").body(accepted.upload());
 }
 @GetMapping("/documents") public ResponseEntity<List<Contracts.Document>> list(@PathVariable UUID projectId,HttpServletRequest request){return ResponseEntity.ok().header("Cache-Control","no-store").body(documents.list(projectId,sessions.require(request).user().id()));}
 @GetMapping("/tasks/{taskId}") public ResponseEntity<Contracts.DocumentTask> task(@PathVariable UUID projectId,@PathVariable UUID taskId,HttpServletRequest request){return ResponseEntity.ok().header("Cache-Control","no-store").body(documents.task(projectId,sessions.require(request).user().id(),taskId));}
}
