package com.researchdesk.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchdesk.identity.ApiFailure;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ExportAdapter {
  private static final int MAX_BYTES = 20 * 1024 * 1024;
  private final URI endpoint;
  private final String token;
  private final ObjectMapper json;
  private final long timeoutMillis;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public ExportAdapter(
      ObjectMapper json,
      @Value("${AI_INTERNAL_URL:http://ai:8000}") String base,
      @Value("${INTERNAL_SERVICE_TOKEN:}") String token,
      @Value("${EXPORT_RENDER_TIMEOUT_MS:90000}") long timeoutMillis) {
    if (timeoutMillis < 1 || timeoutMillis > 90000)
      throw new IllegalArgumentException(
          "Export render timeout may only tighten the 90-second limit");
    this.json = json;
    this.endpoint = URI.create(base + "/internal/reports/export");
    this.token = token;
    this.timeoutMillis = timeoutMillis;
  }

  public byte[] render(ReportService.Version version, String format) {
    long started = System.nanoTime();
    var subscriber = new LimitedBodySubscriber();
    CompletableFuture<HttpResponse<byte[]>> response = null;
    try {
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofMillis(timeoutMillis))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + token)
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      json.writeValueAsString(
                          Map.of(
                              "markdown",
                              version.markdown(),
                              "citations",
                              version.citations(),
                              "format",
                              format))))
              .build();
      response = http.sendAsync(request, ignored -> subscriber);
      long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - started);
      if (remaining <= 0) throw new TimeoutException();
      var rendered = response.get(remaining, TimeUnit.NANOSECONDS);
      if (rendered.statusCode() != 200)
        throw new ApiFailure(
            rendered.statusCode() >= 500 ? 503 : 400, "EXPORT_RENDER_FAILED", "导出渲染失败。");
      byte[] bytes = rendered.body();
      if (bytes.length == 0) throw new ApiFailure(400, "EXPORT_TOO_LARGE", "导出大小超出限制。");
      if (format.equals("PDF")
          && !(bytes.length > 4
              && new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)
                  .equals("%PDF"))) throw new ApiFailure(400, "EXPORT_RENDER_FAILED", "导出内容无效。");
      return bytes;
    } catch (Exception failure) {
      Throwable cause = failure;
      while ((cause instanceof ExecutionException || cause instanceof CompletionException)
          && cause.getCause() != null) cause = cause.getCause();
      if (cause instanceof ApiFailure api) throw api;
      if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
      if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException)
        throw new ApiFailure(503, "EXPORT_RENDER_TIMEOUT", "导出服务响应超时。");
      throw new ApiFailure(503, "EXPORT_RENDER_UNAVAILABLE", "导出服务暂不可用。");
    } finally {
      if (response != null && !response.isDone()) response.cancel(true);
      subscriber.cancel();
    }
  }

  private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private volatile Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      if (body.isDone()) subscription.cancel();
      else subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      if (body.isDone()) return;
      for (var buffer : buffers) {
        int length = buffer.remaining();
        if (length > MAX_BYTES - bytes.size()) {
          body.completeExceptionally(new ApiFailure(400, "EXPORT_TOO_LARGE", "导出大小超出限制。"));
          subscription.cancel();
          return;
        }
        byte[] block = new byte[length];
        buffer.get(block);
        bytes.writeBytes(block);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable failure) {
      body.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
      body.complete(bytes.toByteArray());
    }

    void cancel() {
      body.cancel(true);
      var current = subscription;
      if (current != null) current.cancel();
    }
  }
}
