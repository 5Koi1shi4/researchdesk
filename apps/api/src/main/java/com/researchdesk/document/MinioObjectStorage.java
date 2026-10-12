package com.researchdesk.document;

import com.researchdesk.identity.ApiFailure;
import io.minio.*;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MinioObjectStorage implements ObjectStorage {
 private final MinioClient client; private final String bucket;
 public MinioObjectStorage(@Value("${STORAGE_ENDPOINT:http://localhost:19000}") String endpoint,
  @Value("${STORAGE_ACCESS_KEY:researchdesk-dev}") String access,
  @Value("${STORAGE_SECRET_KEY:researchdesk-dev-only}") String secret,
  @Value("${STORAGE_BUCKET:researchdesk-documents}") String bucket) {
  this.bucket=bucket;client=MinioClient.builder().endpoint(endpoint).credentials(access,secret).build();
  client.setTimeout(3000,10000,10000);
 }
 private synchronized void ensureBucket() throws Exception {
  if(!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
   try { client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build()); }
   catch(io.minio.errors.ErrorResponseException e) { if(!e.errorResponse().code().equals("BucketAlreadyOwnedByYou"))throw e; }
  }
 }
 public void put(String key,Path file,String mediaType) {
  try {ensureBucket();client.uploadObject(UploadObjectArgs.builder().bucket(bucket).object(key).filename(file.toString()).contentType(mediaType).build());}
  catch(Exception e){throw new ApiFailure(503,"STORAGE_UNAVAILABLE","资料存储暂时不可用，请稍后重新上传。");}
 }
 public void delete(String key) {
  try {client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());}
  catch(Exception e){throw new ApiFailure(503,"STORAGE_UNAVAILABLE","资料存储暂时不可用，请稍后重试。");}
 }
}
