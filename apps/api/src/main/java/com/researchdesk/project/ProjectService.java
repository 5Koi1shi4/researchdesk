package com.researchdesk.project;

import com.researchdesk.identity.ApiFailure;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;

@Service
public class ProjectService {
    public record Project(UUID id, String name, Instant createdAt) {}
    private final JdbcTemplate db;
    private final RowMapper<Project> mapper=(row,index) -> new Project(row.getObject("id",UUID.class),row.getString("name"),row.getTimestamp("created_at").toInstant());
    public ProjectService(JdbcTemplate db) { this.db=db; }
    public Project create(UUID owner,String name) {
        if (name == null || name.strip().isEmpty() || name.strip().length()>120) throw new ApiFailure(400,"INVALID_PROJECT_NAME","项目名称须为 1–120 个字符。");
        UUID id=UUID.randomUUID();
        return db.queryForObject("INSERT INTO business.projects(id,owner_id,name) VALUES (?,?,?) RETURNING id,name,created_at",mapper,id,owner,name.strip());
    }
    public List<Project> list(UUID owner) { return db.query("SELECT id,name,created_at FROM business.projects WHERE owner_id=? ORDER BY created_at DESC,id",mapper,owner); }
    public Project findByIdAndOwnerId(UUID id, UUID owner) {
        return db.query("SELECT id,name,created_at FROM business.projects WHERE id=? AND owner_id=?",mapper,id,owner).stream().findFirst().orElseThrow(() -> new ApiFailure(404,"PROJECT_NOT_FOUND","项目不存在。"));
    }
}
