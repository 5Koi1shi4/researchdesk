package com.researchdesk.report;

import com.researchdesk.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReportController {
  private final ReportService reports;
  private final SessionService sessions;

  public ReportController(ReportService r, SessionService s) {
    reports = r;
    sessions = s;
  }

  @ModelAttribute
  void noStore(jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
  }

  record Create(String title, String markdown) {}

  record Save(int baseVersion, String markdown) {}

  record Copy(int version, String markdown) {}

  private UUID read(HttpServletRequest r) {
    return sessions.require(r).user().id();
  }

  private UUID write(HttpServletRequest r) {
    AuthController.sameOrigin(r);
    return sessions.requireWrite(r).user().id();
  }

  @GetMapping("/api/projects/{projectId}/reports")
  public List<ReportService.Report> list(@PathVariable UUID projectId, HttpServletRequest r) {
    return reports.list(projectId, read(r));
  }

  @PostMapping("/api/projects/{projectId}/reports")
  public ResponseEntity<ReportService.Version> create(
      @PathVariable UUID projectId, @RequestBody Create b, HttpServletRequest r) {
    return ResponseEntity.status(201)
        .body(reports.create(projectId, write(r), b.title(), b.markdown()));
  }

  @GetMapping("/api/reports/{id}")
  public ReportService.Report report(@PathVariable UUID id, HttpServletRequest r) {
    return reports.require(id, read(r));
  }

  @GetMapping("/api/reports/{id}/versions")
  public List<ReportService.Version> versions(@PathVariable UUID id, HttpServletRequest r) {
    return reports.versions(id, read(r));
  }

  @GetMapping("/api/reports/{id}/versions/{version}")
  public ReportService.Version version(
      @PathVariable UUID id, @PathVariable int version, HttpServletRequest r) {
    return reports.version(id, read(r), version);
  }

  @PostMapping("/api/reports/{id}/versions")
  public ResponseEntity<ReportService.Version> save(
      @PathVariable UUID id, @RequestBody Save b, HttpServletRequest r) {
    return ResponseEntity.status(201)
        .body(reports.save(id, write(r), b.baseVersion(), b.markdown()));
  }

  @PostMapping("/api/reports/{id}/copies")
  public ResponseEntity<ReportService.Version> copy(
      @PathVariable UUID id, @RequestBody Copy b, HttpServletRequest r) {
    return ResponseEntity.status(201).body(reports.copy(id, write(r), b.version(), b.markdown()));
  }
}
