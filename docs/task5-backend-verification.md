# Task 5 backend verification

Scope: Java-authorized original bytes/source metadata, batched historical citation availability, PDF page PNG preview, immediate source revocation and durable retryable physical cleanup. All runtime operations use the isolated Compose project `researchdesk-sources` on port 18081. No real AI provider was configured or evaluated.

## Implementation and integration

- `GET /api/document-versions/{id}/source-info` resolves version IDs to the original document and reports DELETING/DELETED as DELETED.
- `GET /api/documents/{id}/source` proxies bounded original bytes, with no-store/private, nosniff and restrictive sandbox CSP. Markdown is plain UTF-8 text.
- `GET /api/documents/{id}/source/pages/{page}` renders PDFBox PNG in a child JVM (2 concurrent renders, 128 MiB heap, 96 MiB metaspace, 10 second timeout, maximum 1600 by 1600 pixels, bounded PNG size). Read authorization/state is checked again after rendering. Browser code does not execute original PDF content.
- `POST /api/sources/availability` accepts up to 200 canonical Citation snapshots. It reauthorizes every version and projects current availability; deleted excerpt becomes null. This read-only POST requires the session but does not mutate state or require CSRF. This endpoint is not citation evidence validation: generation must continue validating chunk IDs against its authorized retrieved evidence.
- `DELETE /api/documents/{id}` requires same-origin and CSRF. The transaction marks DELETING and durably queues cleanup. Existing index invalidation cancels active attempts. Cleanup deletes inactive retrieval indexes via a narrowly granted SECURITY DEFINER capability, removes objects and marks DELETED; failure leaves the durable queue and retries after ten seconds.
- Task retry now also requires document state FAILED, preventing deleted failed tasks from returning to PROCESSING. Old document/version rows remain for citation history. V005 changes digest uniqueness to active documents so a new upload creates fresh IDs after deletion.
- Generation/report consumers must call the availability projection when rendering historic citations and recheck effective document state before persisting a completed generation. Their implementations are separate tasks. SourceViewer consumes source-info and the byte/PNG endpoints.

## Red tests observed

1. New DocumentDeletionIT source GET expected 200, actual 404 against the baseline API.
2. Deleted failed task retry expected 409, actual 202 against initial source API (fixture used real INGESTION_UNAVAILABLE failure shape).
3. Delete then identical upload expected 201 and new document ID, actual 200 with the old DELETED document.
4. PdfPreviewTest initially failed compilation because the new preview API/class did not exist; the implemented unit test subsequently rendered an extreme 100000 by 100000 page within the pixel budget and rejected invalid page numbers. This was an absent-API compile failure rather than an assertion-level red.

An earlier invalid FAILED fixture omitted error_code and exposed an existing null handling error (500); the fixture was corrected before using the red result to justify the lifecycle guard, and the guard also handles null safely.

## Commands and results

Working directory for wrapper commands: `E:\test\work\researchdesk-parallel\sources\apps\api`.
Java: `C:\Program Files\Microsoft\jdk-25.0.4.7-hotspot`; Maven cache/home are this clone's `.cache`.

- `./scripts/test-api.ps1` (clone root): initial 9 existing unit tests passed.
- `./scripts/test-api.ps1 -Test DocumentDeletionIT -Integration`: baseline source GET red as above.
- `./mvnw.cmd -Dmaven.repo.local=E:/test/work/researchdesk-parallel/sources/.cache/m2 -B -ntp test-compile failsafe:integration-test failsafe:verify -Dit.test=DocumentDeletionIT#deletedFailedTaskCannotBeRetriedAndStorageFailureLeavesDurableCleanup`: expected 409 vs actual 202 red.
- Same targeted command with `#sameContentCanBeUploadedAgainWithoutRevivingOldCitation`: expected 201 vs actual 200 red.
- Final `./scripts/test-api.ps1 -Test DocumentDeletionIT -Integration` on a newly initialized isolated sources database: 10 unit tests and 4 integration tests passed, zero failures/errors/skips, BUILD SUCCESS at 2026-10-12 13:09:37 Asia/Shanghai. The four integration cases cover authorization/source bytes/citation revocation and idempotency, real MinIO outage and durable cleanup recovery plus retry rejection, same-content reupload with new IDs, and actual authorized bounded PDF page PNG decoding/revocation. PostgreSQL Flyway history confirmed V001–V005 all successful.

Integration tests default to `source.baseUrl=http://localhost:18081` and `source.composeProject=researchdesk-sources`; integrations can explicitly override those system properties. The storage fault test stops/starts only the selected Compose project's MinIO service, restores it in finally, and checks that failed physical cleanup remains DELETING with a durable retry attempt. Do not run upload browser tests concurrently with that fault injection.

No claims are made about Task 6 retrieval implementation, Task 7/8 final persistence race checks, or real model quality. Index inactivation/chunk removal are validated directly against real PostgreSQL/pgvector; storage deletion uses real MinIO.
