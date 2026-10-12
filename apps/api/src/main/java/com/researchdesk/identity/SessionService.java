package com.researchdesk.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {
    public static final String COOKIE = "researchdesk_session";
    private final JdbcTemplate db;
    private final long seconds;
    private final SecureRandom random = new SecureRandom();
    public record User(UUID id, String email) {}
    public record Principal(User user, String csrfToken, String tokenHash) {}
    public record SessionView(User user, String csrfToken) {}
    public record Issued(String token, SessionView view) {}
    public SessionService(JdbcTemplate db, @Value("${researchdesk.auth.session-seconds}") long seconds) { this.db = db; this.seconds = seconds; }
    public long seconds() { return seconds; }
    public String token(HttpServletRequest request) {
        if (request.getCookies() != null) for (var cookie : request.getCookies()) if (COOKIE.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
    public Optional<Principal> lookup(HttpServletRequest request) {
        String token = token(request);
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        return db.query("SELECT s.token_hash,s.csrf_token,u.id,u.email FROM business.sessions s JOIN business.users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>now()",
            (row, index) -> new Principal(new User(row.getObject("id", UUID.class), row.getString("email")), row.getString("csrf_token"), row.getString("token_hash")), hash(token)).stream().findFirst();
    }
    public Principal require(HttpServletRequest request) { return lookup(request).orElseThrow(() -> new ApiFailure(401, "UNAUTHENTICATED", "请登录后继续。")); }
    public Principal requireWrite(HttpServletRequest request) { var principal = require(request); checkCsrf(request, principal); return principal; }
    public void checkCsrf(HttpServletRequest request, Principal principal) {
        String supplied = request.getHeader("X-CSRF-Token");
        if (supplied == null || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), principal.csrfToken().getBytes(StandardCharsets.UTF_8)))
            throw new ApiFailure(403, "CSRF_REQUIRED", "页面验证已失效，请刷新后重试。");
    }
    @Transactional
    public Issued issue(User user, HttpServletRequest request) {
        revoke(request);
        db.update("DELETE FROM business.sessions WHERE expires_at <= now()");
        String token = randomToken(), csrf = randomToken();
        db.update("INSERT INTO business.sessions(token_hash,user_id,csrf_token,expires_at) VALUES (?,?,?,?)", hash(token), user.id(), csrf, java.sql.Timestamp.from(Instant.now().plusSeconds(seconds)));
        return new Issued(token, new SessionView(user, csrf));
    }
    public void revoke(HttpServletRequest request) {
        String token = token(request);
        if (token != null) db.update("DELETE FROM business.sessions WHERE token_hash=?", hash(token));
    }
    private String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
