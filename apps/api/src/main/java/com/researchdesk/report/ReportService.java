package com.researchdesk.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchdesk.Contracts.Availability;
import com.researchdesk.Contracts.Citation;
import com.researchdesk.identity.ApiFailure;
import com.researchdesk.project.ProjectService;
import java.util.*;
import java.util.regex.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
  public record Report(
      UUID id, UUID projectId, String title, int currentVersion, String createdAt) {}

  public record Version(
      UUID reportId,
      int version,
      String markdown,
      List<Citation> citations,
      String createdAt,
      boolean demo) {}

  private final JdbcTemplate db;
  private final ProjectService projects;
  private final ObjectMapper json;

  public ReportService(JdbcTemplate db, ProjectService projects, ObjectMapper json) {
    this.db = db;
    this.projects = projects;
    this.json = json;
  }

  private final RowMapper<Report> report =
      (r, n) ->
          new Report(
              r.getObject("id", UUID.class),
              r.getObject("project_id", UUID.class),
              r.getString("title"),
              r.getInt("current_version"),
              r.getTimestamp("created_at").toInstant().toString());

  private Version map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    try {
      return new Version(
          r.getObject("report_id", UUID.class),
          r.getInt("version"),
          r.getString("markdown"),
          Arrays.asList(json.readValue(r.getString("citations"), Citation[].class)),
          r.getTimestamp("created_at").toInstant().toString(),
          r.getBoolean("demo"));
    } catch (Exception e) {
      throw new java.sql.SQLException(e);
    }
  }

  public Report require(UUID id, UUID owner) {
    return db
        .query(
            "SELECT r.* FROM business.reports r JOIN business.projects p ON p.id=r.project_id WHERE"
                + " r.id=? AND p.owner_id=?",
            report,
            id,
            owner)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiFailure(404, "REPORT_NOT_FOUND", "报告不存在。"));
  }

  public List<Report> list(UUID project, UUID owner) {
    projects.findByIdAndOwnerId(project, owner);
    return db.query(
        "SELECT * FROM business.reports WHERE project_id=? ORDER BY created_at DESC,id",
        report,
        project);
  }

  public List<Version> versions(UUID id, UUID owner) {
    require(id, owner);
    return db
        .query(
            "SELECT * FROM business.report_versions WHERE report_id=? ORDER BY version DESC",
            this::map,
            id)
        .stream()
        .map(this::availability)
        .toList();
  }

  public Version version(UUID id, UUID owner, int number) {
    require(id, owner);
    return availability(raw(id, number));
  }

  private Version raw(UUID id, int number) {
    return db
        .query(
            "SELECT * FROM business.report_versions WHERE report_id=? AND version=?",
            this::map,
            id,
            number)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiFailure(404, "REPORT_VERSION_NOT_FOUND", "报告版本不存在。"));
  }

  private Version availability(Version v) {
    var cs =
        v.citations().stream()
            .map(
                c -> {
                  boolean ready =
                      Boolean.TRUE.equals(
                          db.queryForObject(
                              "SELECT EXISTS(SELECT 1 FROM business.document_versions v JOIN"
                                  + " business.documents d ON d.id=v.document_id WHERE v.id=? AND"
                                  + " d.state='READY')",
                              Boolean.class,
                              UUID.fromString(c.documentVersionId())));
                  return new Citation(
                      c.chunkId(),
                      c.documentVersionId(),
                      c.title(),
                      c.page(),
                      c.paragraph(),
                      ready ? c.excerpt() : null,
                      ready ? Availability.AVAILABLE : Availability.DELETED);
                })
            .toList();
    return new Version(v.reportId(), v.version(), v.markdown(), cs, v.createdAt(), v.demo());
  }

  static Set<String> markers(String markdown) {
    if (markdown == null || markdown.length() > 200000)
      throw new ApiFailure(400, "INVALID_MARKDOWN", "报告正文过长或缺失。");
    var m = Pattern.compile("\\[\\[cite:([^]\\r\\n]+)]]").matcher(markdown);
    Set<String> ids = new LinkedHashSet<>();
    int consumed = 0;
    while (m.find()) {
      consumed++;
      try {
        ids.add(UUID.fromString(m.group(1)).toString());
      } catch (Exception e) {
        throw new ApiFailure(400, "INVALID_CITATION", "引用标记无效。");
      }
    }
    if (markdown.split("\\[\\[cite:", -1).length - 1 != consumed)
      throw new ApiFailure(400, "INVALID_CITATION", "引用标记无效。");
    return ids;
  }

  private List<Citation> selected(String md, List<Citation> cs) {
    Set<String> ids = markers(md);
    Map<String, Citation> known = new HashMap<>();
    for (var c : cs) known.put(c.chunkId(), c);
    if (!known.keySet().containsAll(ids))
      throw new ApiFailure(400, "INVALID_CITATION", "引用不属于此报告。");
    return ids.stream().map(known::get).toList();
  }

  private void insert(UUID id, int number, String md, List<Citation> cs, boolean demo) {
    try {
      db.update(
          "INSERT INTO business.report_versions(report_id,version,markdown,citations,demo)"
              + " VALUES(?,?,?,?::jsonb,?)",
          id,
          number,
          md,
          json.writeValueAsString(cs),
          demo);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @Transactional
  public Version create(UUID project, UUID owner, String title, String markdown) {
    return createInternal(project, owner, null, title, markdown, List.of(), false);
  }

  private Version createInternal(
      UUID project,
      UUID owner,
      UUID run,
      String title,
      String md,
      List<Citation> cs,
      boolean demo) {
    projects.findByIdAndOwnerId(project, owner);
    if (title == null || title.strip().isEmpty() || title.length() > 200)
      throw new ApiFailure(400, "INVALID_REPORT_TITLE", "报告标题须为 1–200 字符。");
    var selected = selected(md, cs);
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO business.reports(id,project_id,title,source_run_id) VALUES(?,?,?,?)",
        id,
        project,
        title.strip(),
        run);
    insert(id, 1, md, selected, demo);
    return availability(raw(id, 1));
  }

  @Transactional
  public Version save(UUID id, UUID owner, int base, String md) {
    require(id, owner);
    var old = raw(id, base);
    var cs = selected(md, old.citations());
    if (db.update(
            "UPDATE business.reports SET current_version=current_version+1 WHERE id=? AND"
                + " current_version=?",
            id,
            base)
        != 1) throw new ApiFailure(409, "VERSION_CONFLICT", "报告已有更新，请另存副本或重新加载。");
    insert(id, base + 1, md, cs, old.demo());
    return availability(raw(id, base + 1));
  }

  @Transactional
  public Version copy(UUID id, UUID owner, int version, String md) {
    var r = require(id, owner);
    var old = raw(id, version);
    return createInternal(
        r.projectId(),
        owner,
        null,
        r.title().substring(0, Math.min(197, r.title().length())) + " 副本",
        md,
        old.citations(),
        old.demo());
  }

  /** Called only after the generation lifecycle validates its run fence and authorized scope. */
  @Transactional
  public Version completeDraft(
      UUID project,
      UUID owner,
      UUID run,
      String title,
      String md,
      List<Citation> supplied,
      List<UUID> allowed,
      boolean demo) {
    projects.findByIdAndOwnerId(project, owner);
    db.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        (row, index) -> null,
        run.toString());
    var existing =
        db.query(
            "SELECT id FROM business.reports WHERE source_run_id=? AND project_id=?",
            (r, n) -> r.getObject(1, UUID.class),
            run,
            project);
    if (!existing.isEmpty()) return availability(raw(existing.getFirst(), 1));
    List<Citation> canonical = new ArrayList<>();
    for (var c : supplied) {
      UUID v;
      UUID chunk;
      try {
        v = UUID.fromString(c.documentVersionId());
        chunk = UUID.fromString(c.chunkId());
      } catch (Exception e) {
        throw new ApiFailure(400, "INVALID_CITATION", "引用标识无效。");
      }
      if (!allowed.contains(v)) throw new ApiFailure(400, "INVALID_CITATION", "引用超出授权资料。");
      var locked =
          db.query(
              "SELECT d.id FROM business.documents d JOIN business.document_versions v ON"
                  + " v.document_id=d.id AND v.project_id=d.project_id WHERE v.id=? AND"
                  + " v.project_id=? AND d.state='READY' FOR SHARE OF d",
              (r, n) -> r.getObject(1, UUID.class),
              v,
              project);
      if (locked.isEmpty()) throw new ApiFailure(400, "INVALID_CITATION", "引用资料不可用。");
      var rows =
          db.query(
              "SELECT * FROM business.report_citation(?,?,?)",
              (r, n) ->
                  new Citation(
                      r.getObject("chunk_id").toString(),
                      r.getObject("document_version_id").toString(),
                      r.getString("title"),
                      r.getObject("page", Integer.class),
                      r.getObject("paragraph", Integer.class),
                      r.getString("excerpt"),
                      Availability.AVAILABLE),
              project,
              v,
              chunk);
      if (rows.isEmpty()) throw new ApiFailure(400, "INVALID_CITATION", "引用资料不可用。");
      canonical.add(rows.getFirst());
    }
    return createInternal(project, owner, run, title, md, canonical, demo);
  }
}
