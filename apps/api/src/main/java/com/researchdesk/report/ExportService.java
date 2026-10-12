package com.researchdesk.report;

import com.researchdesk.Contracts.TaskState;
import com.researchdesk.identity.ApiFailure;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ExportService {
  public record Export(
      UUID id,
      UUID reportId,
      int version,
      String format,
      TaskState state,
      String errorCode,
      String createdAt) {}

  record Work(
      UUID id,
      UUID reportId,
      int version,
      String format,
      String objectKey,
      long fence,
      int attempts,
      UUID owner) {}

  private final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper();
  private final org.springframework.transaction.support.TransactionTemplate transaction;
  private final JdbcTemplate db;
  private final ReportService reports;
  private final ExportAdapter adapter;
  private final ExportStorage storage;

  public ExportService(
      JdbcTemplate db,
      ReportService reports,
      ExportAdapter adapter,
      ExportStorage storage,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
    this.db = db;
    this.reports = reports;
    this.adapter = adapter;
    this.storage = storage;
  }

  private final RowMapper<Export> mapper =
      (r, n) ->
          new Export(
              r.getObject("id", UUID.class),
              r.getObject("report_id", UUID.class),
              r.getInt("version"),
              r.getString("format"),
              TaskState.valueOf(r.getString("state")),
              r.getString("error_code"),
              r.getTimestamp("created_at").toInstant().toString());

  public Export create(UUID report, UUID owner, int version, String format) {
    reports.version(report, owner, version);
    if (!Set.of("MARKDOWN", "PDF").contains(format == null ? "" : format))
      throw new ApiFailure(400, "INVALID_EXPORT_FORMAT", "导出格式无效。");
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO business.report_exports(id,report_id,version,format,object_key)"
            + " VALUES(?,?,?,?,?)",
        id,
        report,
        version,
        format,
        "reports/" + report + "/exports/" + id);
    return get(report, owner, id);
  }

  public Export get(UUID report, UUID owner, UUID id) {
    reports.require(report, owner);
    return db
        .query(
            "SELECT * FROM business.report_exports WHERE id=? AND report_id=?", mapper, id, report)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiFailure(404, "EXPORT_NOT_FOUND", "导出任务不存在。"));
  }

  public byte[] download(UUID report, UUID owner, UUID id) {
    return transaction.execute(status -> downloadLocked(report, owner, id));
  }

  private byte[] downloadLocked(UUID report, UUID owner, UUID id) {
    var e = get(report, owner, id);
    if (e.state() != TaskState.SUCCEEDED) throw new ApiFailure(409, "EXPORT_NOT_READY", "导出尚未完成。");
    String snapshot =
        db.queryForObject(
            "SELECT citation_snapshot::text FROM business.report_exports WHERE id=?",
            String.class,
            id);
    try {
      var prior = json.readValue(snapshot, com.researchdesk.Contracts.Citation[].class);
      for (var c : prior)
        db.query(
            "SELECT d.id FROM business.documents d JOIN business.document_versions v ON"
                + " v.document_id=d.id WHERE v.id=? FOR SHARE OF d",
            (r, n) -> r.getObject(1),
            UUID.fromString(c.documentVersionId()));
      var current = reports.version(report, owner, e.version()).citations();
      for (var c : prior)
        if (c.availability() == com.researchdesk.Contracts.Availability.AVAILABLE
            && current.stream()
                .anyMatch(
                    now ->
                        now.chunkId().equals(c.chunkId())
                            && now.availability()
                                == com.researchdesk.Contracts.Availability.DELETED))
          throw new ApiFailure(409, "EXPORT_SOURCE_CHANGED", "来源已删除，请重新导出。");
    } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
      throw new IllegalStateException(malformed);
    }
    String key =
        db.queryForObject(
            "SELECT object_key FROM business.report_exports WHERE id=?", String.class, id);
    return storage.read(key);
  }

  @Scheduled(fixedDelay = 1000)
  public void dispatch() {
    List<Work> jobs;
    try {
      jobs =
          db.query(
              "UPDATE business.report_exports e SET"
                  + " state='RUNNING',attempts=attempts+1,fence=fence+1,lease_until=now()+interval"
                  + " '120 seconds',error_code=NULL WHERE id=(SELECT id FROM"
                  + " business.report_exports WHERE ((state='QUEUED' AND available_at<=now()) OR"
                  + " (state='RUNNING' AND lease_until<now())) AND attempts<4 ORDER BY"
                  + " available_at,id FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING e.*, (SELECT"
                  + " p.owner_id FROM business.reports r JOIN business.projects p ON"
                  + " p.id=r.project_id WHERE r.id=e.report_id) owner_id",
              (r, n) ->
                  new Work(
                      r.getObject("id", UUID.class),
                      r.getObject("report_id", UUID.class),
                      r.getInt("version"),
                      r.getString("format"),
                      r.getString("object_key"),
                      r.getLong("fence"),
                      r.getInt("attempts"),
                      r.getObject("owner_id", UUID.class)));
      db.update(
          "UPDATE business.report_exports SET"
              + " state='FAILED',error_code='EXPORT_RETRIES_EXHAUSTED',lease_until=NULL WHERE"
              + " state='RUNNING' AND lease_until<now() AND attempts>=4");
    } catch (Exception unavailable) {
      return;
    }
    for (var w : jobs) {
      try {
        var version = reports.version(w.reportId(), w.owner(), w.version());
        var bytes = adapter.render(version, w.format());
        if (!version
            .citations()
            .equals(reports.version(w.reportId(), w.owner(), w.version()).citations()))
          throw new ApiFailure(503, "EXPORT_SOURCE_CHANGED", "来源状态已变化，请重新导出。");
        String key = "reports/" + w.reportId() + "/exports/" + w.id() + "-" + w.fence();
        try (var objectLock = lockObject(key)) {
          db.update(
              "INSERT INTO business.storage_cleanup(object_key) VALUES(?) ON CONFLICT DO NOTHING",
              key);
          storage.put(
              key,
              bytes,
              w.format().equals("PDF") ? "application/pdf" : "text/markdown; charset=utf-8");
          String snapshot = json.writeValueAsString(version.citations());
          transaction.executeWithoutResult(
              status -> {
                for (var c : version.citations())
                  db.query(
                      "SELECT d.id FROM business.documents d JOIN business.document_versions v ON"
                          + " v.document_id=d.id WHERE v.id=? FOR SHARE OF d",
                      (r, n) -> r.getObject(1),
                      UUID.fromString(c.documentVersionId()));
                if (!version
                    .citations()
                    .equals(reports.version(w.reportId(), w.owner(), w.version()).citations()))
                  throw new ApiFailure(503, "EXPORT_SOURCE_CHANGED", "来源状态已变化，请重新导出。");
                int published =
                    db.update(
                        "UPDATE business.report_exports SET"
                            + " state='SUCCEEDED',lease_until=NULL,object_key=?,citation_snapshot=?::jsonb"
                            + " WHERE id=? AND fence=? AND state='RUNNING' AND lease_until>now()",
                        key,
                        snapshot,
                        w.id(),
                        w.fence());
                if (published == 1)
                  db.update("DELETE FROM business.storage_cleanup WHERE object_key=?", key);
              });
        }
      } catch (Exception failure) {
        boolean retry = failure instanceof ApiFailure a && a.status >= 500 && w.attempts() < 4;
        String code = failure instanceof ApiFailure a ? a.code : "EXPORT_FAILED";
        db.update(
            "UPDATE business.report_exports SET"
                + " state=?,error_code=?,lease_until=NULL,available_at=now()+(? * interval '1"
                + " second') WHERE id=? AND fence=? AND state='RUNNING'",
            retry ? "QUEUED" : "FAILED",
            code,
            retry ? 1L << w.attempts() : 0L,
            w.id(),
            w.fence());
      }
    }
  }

  private ObjectLock lockObject(String key) {
    try {
      return new ObjectLock(Objects.requireNonNull(db.getDataSource()).getConnection(), key);
    } catch (java.sql.SQLException failure) {
      throw new ApiFailure(503, "STORAGE_UNAVAILABLE", "导出存储暂不可用。");
    }
  }

  private static final class ObjectLock implements AutoCloseable {
    private final java.sql.Connection connection;
    private final String key;

    ObjectLock(java.sql.Connection connection, String key) throws java.sql.SQLException {
      this.connection = connection;
      this.key = key;
      try (var query =
          connection.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?,0))")) {
        query.setString(1, key);
        query.execute();
      } catch (java.sql.SQLException failure) {
        connection.close();
        throw failure;
      }
    }

    @Override
    public void close() {
      try {
        try (var query =
            connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?,0))")) {
          query.setString(1, key);
          query.execute();
        }
      } catch (java.sql.SQLException failure) {
        try {
          connection.abort(Runnable::run);
        } catch (java.sql.SQLException ignored) {
        }
      } finally {
        try {
          connection.close();
        } catch (java.sql.SQLException ignored) {
        }
      }
    }
  }
}
