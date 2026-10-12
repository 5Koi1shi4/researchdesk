package com.researchdesk.report;

import com.researchdesk.document.ObjectStorage;
import com.researchdesk.identity.ApiFailure;
import io.minio.*;
import java.io.*;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Private object reads are proxied only after ExportService authorization. */
@Component
public class ExportStorage {
  private final ObjectStorage storage;
  private final MinioClient client;
  private final String bucket;

  public ExportStorage(
      ObjectStorage storage,
      @Value("${STORAGE_ENDPOINT:http://localhost:19000}") String endpoint,
      @Value("${STORAGE_ACCESS_KEY:researchdesk-dev}") String access,
      @Value("${STORAGE_SECRET_KEY:researchdesk-dev-only}") String secret,
      @Value("${STORAGE_BUCKET:researchdesk-documents}") String bucket) {
    this.storage = storage;
    this.bucket = bucket;
    client = MinioClient.builder().endpoint(endpoint).credentials(access, secret).build();
    client.setTimeout(3000, 10000, 10000);
  }

  public void put(String key, byte[] bytes, String type) {
    Path temp = null;
    try {
      temp = Files.createTempFile("report-export-", ".bin");
      Files.write(temp, bytes);
      storage.put(key, temp, type);
    } catch (IOException e) {
      throw new ApiFailure(503, "STORAGE_UNAVAILABLE", "导出存储暂不可用。");
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
        }
    }
  }

  public byte[] read(String key) {
    try (var in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
      var bytes = in.readNBytes(20 * 1024 * 1024 + 1);
      if (bytes.length > 20 * 1024 * 1024) throw new IOException("oversize");
      return bytes;
    } catch (Exception e) {
      throw new ApiFailure(503, "STORAGE_UNAVAILABLE", "导出存储暂不可用。");
    }
  }
}
