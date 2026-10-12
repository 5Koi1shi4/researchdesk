package com.researchdesk.report;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchdesk.identity.ApiFailure;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(ExportAdapterTest.Config.class)
@TestPropertySource(
    properties = {"INTERNAL_SERVICE_TOKEN=export-test", "EXPORT_RENDER_TIMEOUT_MS=250"})
class ExportAdapterTest {
  private static final AtomicReference<String> response = new AtomicReference<>();
  private static final ExecutorService serverThreads =
      Executors.newCachedThreadPool(
          task -> {
            var thread = new Thread(task, "export-adapter-test-server");
            thread.setDaemon(true);
            return thread;
          });
  private static final HttpServer server = startServer();

  @Configuration
  @Import(ExportAdapter.class)
  static class Config {
    @Bean
    ObjectMapper json() {
      return new ObjectMapper();
    }
  }

  @DynamicPropertySource
  static void endpoint(DynamicPropertyRegistry properties) {
    properties.add("AI_INTERNAL_URL", () -> "http://127.0.0.1:" + server.getAddress().getPort());
  }

  private static HttpServer startServer() {
    try {
      var result = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      result.setExecutor(serverThreads);
      result.createContext(
          "/internal/reports/export",
          exchange -> {
            exchange.getRequestBody().readAllBytes();
            try (var output = exchange.getResponseBody()) {
              if (response.get().equals("stall")) {
                exchange.sendResponseHeaders(200, 0);
                output.write("partial".getBytes(StandardCharsets.UTF_8));
                output.flush();
                Thread.sleep(2000);
                output.write("late body".getBytes(StandardCharsets.UTF_8));
              } else {
                int remaining = 20 * 1024 * 1024 + 1;
                exchange.sendResponseHeaders(200, remaining);
                byte[] block = new byte[16384];
                while (remaining > 0) {
                  int length = Math.min(block.length, remaining);
                  output.write(block, 0, length);
                  remaining -= length;
                }
              }
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            } catch (IOException cancelled) {
              // The client cancels the response once its deadline or size limit is reached.
            } finally {
              exchange.close();
            }
          });
      result.start();
      return result;
    } catch (IOException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  @AfterAll
  static void closeServer() {
    server.stop(0);
    serverThreads.shutdownNow();
  }

  @Autowired ExportAdapter adapter;

  private ReportService.Version version() {
    return new ReportService.Version(UUID.randomUUID(), 1, "draft", List.of(), "fixture", false);
  }

  @Test
  void fullResponseDeadlineIncludesBodyAfterHeadersArrive() throws Exception {
    response.set("stall");
    var worker = Executors.newSingleThreadExecutor();
    var rendering = worker.submit(() -> adapter.render(version(), "MARKDOWN"));
    long started = System.nanoTime();
    try {
      assertThatThrownBy(() -> rendering.get(1200, TimeUnit.MILLISECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(ApiFailure.class)
          .satisfies(
              failure -> assertThat(((ApiFailure) failure.getCause()).status).isEqualTo(503));
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    } finally {
      rendering.cancel(true);
      worker.shutdownNow();
    }
  }

  @Test
  void responseLargerThanTwentyMiBIsRejectedWithoutPublishingBytes() {
    response.set("oversize");
    var sizeLimited =
        new ExportAdapter(
            new ObjectMapper(),
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "export-test",
            3000);
    assertThatThrownBy(() -> sizeLimited.render(version(), "MARKDOWN"))
        .isInstanceOf(ApiFailure.class)
        .satisfies(
            failure -> {
              assertThat(((ApiFailure) failure).status).isEqualTo(400);
              assertThat(((ApiFailure) failure).code).isEqualTo("EXPORT_TOO_LARGE");
            });
  }
}
