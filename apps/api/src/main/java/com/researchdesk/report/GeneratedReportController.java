package com.researchdesk.report;

import com.researchdesk.identity.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class GeneratedReportController {
 private final GeneratedReportLookup lookup;private final SessionService sessions;
 public GeneratedReportController(GeneratedReportLookup lookup,SessionService sessions){this.lookup=lookup;this.sessions=sessions;}
 @GetMapping("/api/runs/{runId}/report")
 public ResponseEntity<ReportService.Report> report(@PathVariable UUID runId,HttpServletRequest request){
  return ResponseEntity.ok().header("Cache-Control","no-store").body(lookup.find(runId,sessions.require(request).user().id()));
 }
}
