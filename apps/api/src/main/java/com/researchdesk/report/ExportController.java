package com.researchdesk.report;

import com.researchdesk.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports/{id}/exports")
public class ExportController {
  private final ExportService exports;
  private final SessionService sessions;

  public ExportController(ExportService e, SessionService s) {
    exports = e;
    sessions = s;
  }

  record Request(int version, String format) {}

  @PostMapping
  public ResponseEntity<ExportService.Export> create(
      @PathVariable UUID id, @RequestBody Request b, HttpServletRequest r) {
    AuthController.sameOrigin(r);
    return ResponseEntity.accepted()
        .header("Cache-Control", "no-store")
        .body(exports.create(id, sessions.requireWrite(r).user().id(), b.version(), b.format()));
  }

  @GetMapping("/{exportId}")
  public ResponseEntity<ExportService.Export> get(
      @PathVariable UUID id, @PathVariable UUID exportId, HttpServletRequest r) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(exports.get(id, sessions.require(r).user().id(), exportId));
  }

  @GetMapping("/{exportId}/download")
  public ResponseEntity<byte[]> download(
      @PathVariable UUID id, @PathVariable UUID exportId, HttpServletRequest r) {
    UUID owner = sessions.require(r).user().id();
    var e = exports.get(id, owner, exportId);
    boolean pdf = e.format().equals("PDF");
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header(
            "Content-Disposition",
            "attachment; filename=report-v" + e.version() + (pdf ? ".pdf" : ".md"))
        .contentType(
            MediaType.parseMediaType(pdf ? "application/pdf" : "text/markdown; charset=utf-8"))
        .body(exports.download(id, owner, exportId));
  }
}
