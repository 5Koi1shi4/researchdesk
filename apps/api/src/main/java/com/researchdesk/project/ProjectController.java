package com.researchdesk.project;

import com.researchdesk.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    public record CreateProject(String name) {}
    private final ProjectService projects;
    private final SessionService sessions;
    public ProjectController(ProjectService projects,SessionService sessions) { this.projects=projects; this.sessions=sessions; }
    @PostMapping public ResponseEntity<ProjectService.Project> create(@RequestBody CreateProject input,HttpServletRequest request) {
        AuthController.sameOrigin(request); var principal=sessions.requireWrite(request);
        return ResponseEntity.status(201).header("Cache-Control","no-store").body(projects.create(principal.user().id(),input.name()));
    }
    @GetMapping public ResponseEntity<List<ProjectService.Project>> list(HttpServletRequest request) { return ResponseEntity.ok().header("Cache-Control","no-store").body(projects.list(sessions.require(request).user().id())); }
    @GetMapping("/{id}") public ResponseEntity<ProjectService.Project> get(@PathVariable UUID id,HttpServletRequest request) { return ResponseEntity.ok().header("Cache-Control","no-store").body(projects.findByIdAndOwnerId(id,sessions.require(request).user().id())); }
}
