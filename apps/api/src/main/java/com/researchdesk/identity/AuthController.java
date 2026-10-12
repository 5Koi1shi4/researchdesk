package com.researchdesk.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record Credentials(String email, String password) {}
    private final JdbcTemplate db;
    private final SessionService sessions;
    private final boolean secure;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String dummyHash = passwords.encode("unknown-account-timing-pad");
    public AuthController(JdbcTemplate db, SessionService sessions, @Value("${researchdesk.auth.cookie-secure}") boolean secure) { this.db=db; this.sessions=sessions; this.secure=secure; }
    public static void sameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String site = request.getHeader("Sec-Fetch-Site");
        if ("cross-site".equals(site)) throw new ApiFailure(403, "ORIGIN_REJECTED", "请在本站重新打开登录页。");
        if (origin != null) {
            try {
                URI uri = URI.create(origin);
                String expected = request.getHeader("Host");
                String scheme = request.getHeader("X-Forwarded-Proto");
                if (scheme == null) scheme = request.getScheme();
                if (!Objects.equals(uri.getRawAuthority(), expected) || !Objects.equals(uri.getScheme(), scheme)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { throw new ApiFailure(403, "ORIGIN_REJECTED", "请在本站重新打开登录页。"); }
        }
    }
    private String validate(Credentials input) {
        if (input == null || input.email() == null || input.password() == null) throw invalid();
        String email = input.email().strip().toLowerCase(Locale.ROOT);
        if (email.length()>254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || input.password().length()<12 || input.password().getBytes(StandardCharsets.UTF_8).length>72) throw invalid();
        return email;
    }
    private ApiFailure invalid() { return new ApiFailure(400, "INVALID_CREDENTIALS", "请输入有效邮箱；密码至少 12 个字符，最多 72 字节。"); }
    private void loginGuard(HttpServletRequest request) { sameOrigin(request); sessions.lookup(request).ifPresent(p -> sessions.checkCsrf(request,p)); }
    // API is internal-only. The edge overwrites X-Real-IP; browsers cannot forge it.
    private String clientIp(HttpServletRequest request) { String ip=request.getHeader("X-Real-IP"); return ip == null ? request.getRemoteAddr() : ip; }
    // Atomic PostgreSQL counter: concurrent attempts cannot bypass the window.
    private void consume(String key, int limit) {
        int attempts = db.queryForObject("INSERT INTO business.auth_throttles(key,attempts,window_start) VALUES (?,1,now()) ON CONFLICT(key) DO UPDATE SET attempts=CASE WHEN business.auth_throttles.window_start<now()-interval '15 minutes' THEN 1 ELSE business.auth_throttles.attempts+1 END, window_start=CASE WHEN business.auth_throttles.window_start<now()-interval '15 minutes' THEN now() ELSE business.auth_throttles.window_start END RETURNING attempts", Integer.class, key);
        if (attempts > limit) throw new ApiFailure(429, "TOO_MANY_ATTEMPTS", "尝试次数过多，请在 15 分钟后重试。");
    }
    @PostMapping("/register")
    public ResponseEntity<SessionService.SessionView> register(@RequestBody Credentials input, HttpServletRequest request) {
        loginGuard(request); String email = validate(input); consume("register:" + clientIp(request), 100);
        var user = new SessionService.User(UUID.randomUUID(), email);
        try { db.update("INSERT INTO business.users(id,email,password_hash) VALUES (?,?,?)", user.id(), email, passwords.encode(input.password())); }
        catch (DuplicateKeyException conflict) { throw new ApiFailure(409, "EMAIL_IN_USE", "该邮箱已注册，请登录。"); }
        return issued(sessions.issue(user, request), 201);
    }
    @PostMapping("/login")
    public ResponseEntity<SessionService.SessionView> login(@RequestBody Credentials input, HttpServletRequest request) {
        loginGuard(request); String email=validate(input); consume("ip:"+clientIp(request),100); consume("email:"+email,5);
        var rows = db.query("SELECT id,email,password_hash FROM business.users WHERE email=?", (row,index) -> new Account(new SessionService.User(row.getObject("id",UUID.class),row.getString("email")),row.getString("password_hash")),email);
        var account = rows.isEmpty() ? null : rows.getFirst();
        boolean matches=passwords.matches(input.password(),account == null ? dummyHash : account.hash());
        if (!matches || account == null) throw new ApiFailure(401,"INVALID_CREDENTIALS","邮箱或密码不正确，请检查后重试。");
        db.update("DELETE FROM business.auth_throttles WHERE key=?", "email:"+email);
        return issued(sessions.issue(account.user(),request),200);
    }
    private record Account(SessionService.User user,String hash) {}
    private ResponseEntity<SessionService.SessionView> issued(SessionService.Issued issued,int status) {
        return ResponseEntity.status(status).header(HttpHeaders.SET_COOKIE,cookie(issued.token(),sessions.seconds())).header(HttpHeaders.CACHE_CONTROL,"no-store").body(issued.view());
    }
    private String cookie(String token,long seconds) { return ResponseCookie.from(SessionService.COOKIE,token).httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(seconds).build().toString(); }
    @GetMapping("/session") public ResponseEntity<SessionService.SessionView> session(HttpServletRequest request) {
        var principal=sessions.require(request);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(new SessionService.SessionView(principal.user(),principal.csrfToken()));
    }
    @PostMapping("/logout") public ResponseEntity<Void> logout(HttpServletRequest request) {
        sameOrigin(request); sessions.requireWrite(request); sessions.revoke(request);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,cookie("",0)).header(HttpHeaders.CACHE_CONTROL,"no-store").build();
    }
}
