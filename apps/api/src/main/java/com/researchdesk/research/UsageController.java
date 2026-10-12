package com.researchdesk.research;
import com.researchdesk.identity.SessionService; import jakarta.servlet.http.HttpServletRequest; import java.util.*;
import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*;
@RestController
public class UsageController {
 private final UsageService usage; private final SessionService sessions;
 public UsageController(UsageService usage,SessionService sessions){this.usage=usage;this.sessions=sessions;}
 @GetMapping("/api/projects/{id}/usage") public ResponseEntity<List<UsageService.UsageRecord>> list(@PathVariable UUID id,HttpServletRequest request){return ResponseEntity.ok().header("Cache-Control","no-store").body(usage.list(sessions.require(request).user().id(),id));}
}
