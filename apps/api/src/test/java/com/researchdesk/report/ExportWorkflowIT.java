package com.researchdesk.report;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchdesk.Contracts.TaskState;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(ReportDraftIT.Config.class)
class ExportWorkflowIT {
  @Autowired JdbcTemplate db;
  @Autowired ReportService reports;
  @Autowired PlatformTransactionManager manager;
  @Autowired DataSource source;
  private HttpServer server;
  private ExecutorService serverThreads;
  private final AtomicInteger httpStatus = new AtomicInteger(200);
  private final AtomicInteger requests = new AtomicInteger();

  @BeforeEach
  void renderer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverThreads =
        Executors.newCachedThreadPool(
            task -> {
              var thread = new Thread(task, "export-workflow-test-server");
              thread.setDaemon(true);
              return thread;
            });
    server.setExecutor(serverThreads);
    server.createContext(
        "/internal/reports/export",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          byte[] bytes = "rendered report".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(httpStatus.get(), bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void closeRenderer() {
    server.stop(0);
    serverThreads.shutdownNow();
  }

  private ExportAdapter adapter() {
    return new ExportAdapter(
        new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), "fixture", 3000);
  }

  private UUID report(UUID owner) {
    UUID project = UUID.randomUUID();
    db.update(
        "INSERT INTO business.users(id,email,password_hash) VALUES(?,?,?)",
        owner,
        "export-workflow-" + owner + "@researchdesk.test",
        "fixture");
    db.update(
        "INSERT INTO business.projects(id,owner_id,name) VALUES(?,?,?)",
        project,
        owner,
        "workflow");
    return reports.create(project, owner, "report", "draft").reportId();
  }

  private void due(UUID export) {
    // Uncommitted fixtures cannot be claimed by the running API. This job sorts first locally.
    db.update(
        "UPDATE business.report_exports SET available_at=now()-interval '1 year' WHERE id=?",
        export);
  }

  @Test
  void transientFailuresUseTwoFourEightSecondDelaysAndStopAfterFourAttempts() {
    httpStatus.set(503);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              status.setRollbackOnly();
              UUID owner = UUID.randomUUID();
              UUID report = report(owner);
              var exports = new ExportService(db, reports, adapter(), null, manager);
              UUID export = exports.create(report, owner, 1, "MARKDOWN").id();
              double[] delays = {2, 4, 8};
              for (int attempt = 1; attempt <= 3; attempt++) {
                due(export);
                exports.dispatch();
                var persisted =
                    db.queryForMap(
                        "SELECT state,attempts,extract(epoch FROM available_at-now())::double"
                            + " precision delay FROM business.report_exports WHERE id=?",
                        export);
                assertThat(persisted.get("state")).isEqualTo("QUEUED");
                assertThat(persisted.get("attempts")).isEqualTo(attempt);
                assertThat(((Number) persisted.get("delay")).doubleValue())
                    .isCloseTo(delays[attempt - 1], within(0.01));
                exports.dispatch();
                assertThat(requests.get()).isEqualTo(attempt);
              }
              due(export);
              exports.dispatch();
              assertThat(exports.get(report, owner, export).state()).isEqualTo(TaskState.FAILED);
              assertThat(
                      db.queryForObject(
                          "SELECT attempts FROM business.report_exports WHERE id=?",
                          Integer.class,
                          export))
                  .isEqualTo(4);
              due(export);
              exports.dispatch();
              assertThat(requests.get()).isEqualTo(4);
            });
  }

  @Test
  void permanentRendererFailureIsNotRetried() {
    httpStatus.set(400);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              status.setRollbackOnly();
              UUID owner = UUID.randomUUID();
              UUID report = report(owner);
              var exports = new ExportService(db, reports, adapter(), null, manager);
              UUID export = exports.create(report, owner, 1, "MARKDOWN").id();
              due(export);
              exports.dispatch();
              assertThat(exports.get(report, owner, export).state()).isEqualTo(TaskState.FAILED);
              due(export);
              exports.dispatch();
              assertThat(requests.get()).isEqualTo(1);
            });
  }

  private boolean cleanupCanLock(String key) {
    try (var connection = source.getConnection();
        var query =
            connection.prepareStatement(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))")) {
      query.setString(1, key);
      try (var result = query.executeQuery()) {
        result.next();
        return result.getBoolean(1);
      }
    } catch (SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }

  @Test
  void cleanupCannotLockObjectDuringPutAndLockIsReleasedAfterPublication() {
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              status.setRollbackOnly();
              UUID owner = UUID.randomUUID();
              UUID report = report(owner);
              var writtenKey = new AtomicReference<String>();
              // Only the external object write is replaced; lease, intent, lock and CAS use
              // PostgreSQL.
              var storage =
                  new ExportStorage(
                      null, "http://127.0.0.1:1", "fixture", "fixture-secret", "reports") {
                    @Override
                    public void put(String key, byte[] bytes, String type) {
                      writtenKey.set(key);
                      assertThat(
                              db.queryForObject(
                                  "SELECT count(*) FROM business.storage_cleanup WHERE"
                                      + " object_key=?",
                                  Integer.class,
                                  key))
                          .isEqualTo(1);
                      assertThat(
                              db.queryForObject(
                                  "SELECT not_before>now() FROM business.storage_cleanup WHERE"
                                      + " object_key=?",
                                  Boolean.class,
                                  key))
                          .isTrue();
                      assertThat(cleanupCanLock(key)).isFalse();
                    }
                  };
              var exports = new ExportService(db, reports, adapter(), storage, manager);
              UUID export = exports.create(report, owner, 1, "MARKDOWN").id();
              due(export);
              exports.dispatch();
              assertThat(exports.get(report, owner, export).state()).isEqualTo(TaskState.SUCCEEDED);
              assertThat(
                      db.queryForObject(
                          "SELECT object_key FROM business.report_exports WHERE id=?",
                          String.class,
                          export))
                  .isEqualTo(writtenKey.get());
              assertThat(
                      db.queryForObject(
                          "SELECT count(*) FROM business.storage_cleanup WHERE object_key=?",
                          Integer.class,
                          writtenKey.get()))
                  .isZero();
              assertThat(cleanupCanLock(writtenKey.get())).isTrue();
            });
  }
}
