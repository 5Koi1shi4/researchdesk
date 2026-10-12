# Source integration contract (Task 5)

## UI

Import `SourceViewer` from `apps/web/src/features/documents/SourceViewer`. Its public prop is `{citation: Citation}` using the canonical generated type. The host controls panel selection/closing. Markdown is displayed as text, numbered exactly like the current parser; PDF uses server-rendered page images. No external resources or raw HTML are rendered.

Before rendering historical citation lists, project saved citation snapshots through `POST /api/sources/availability` with `{citations: Citation[]}` (up to 200 per request). This read-only endpoint requires the current session and owner-authorizes every version. The response preserves snapshot identity/title/location but sets `availability: DELETED` and `excerpt: null` for deleted sources. A foreign or unknown version returns 404 for the whole request. This endpoint does not prove semantic support or validate a user-supplied chunk as evidence; generation must validate evidence separately.

`SourceViewer` itself fetches `GET /api/document-versions/{versionId}/source-info` before displaying an excerpt. Hosts should pass refreshed availability when a deletion becomes known. Already delivered content cannot be recalled from another browser tab; subsequent source requests are denied and all source responses use no-store.

## Read and delete

- `GET /api/documents/{id}/source`: authorized PDF bytes or UTF-8 plain text. Only READY sources can be read. It never returns an object-storage URL.
- `GET /api/documents/{id}/source/pages/{page}`: authorized PNG PDF page, one-based. Rendering runs in a limited child JVM with a 10-second deadline, 128 MiB heap and at most two concurrent renders. The longest image edge is bounded to 1600 pixels.
- `DELETE /api/documents/{id}`: session + CSRF + same-origin validation; returns 202 with `{documentId,state}`. Repeated requests are idempotent.
- Source reads return 410 `SOURCE_DELETED`, 409 `SOURCE_NOT_READY`, and an indistinguishable 404 for unknown/foreign IDs.

Deletion commits DELETING and index invalidation before returning. V005 provides a durable cleanup row; failed vector/object cleanup remains retryable. History records and citation snapshots are retained. The typed database cleanup function only accepts deleted documents and keeps the business account from writing retrieval tables directly.

## Required integration with Tasks 6–10

Retrieval must use both the Java-authorized READY version list and the index `active`/`published` flags in its SQL predicate. Task 5 tests immediate revocation and physical removal of indexes; the retrieval ranking module lives in a separate branch.

Generation must recheck source states in its final persistence transaction while taking document locks in deterministic ID order. Deletion and ingestion take document locks before task/index locks. A prior availability response alone is not an atomic final-generation guard. Final completion versus deletion races require the generation branch's integrated tests.

Report/answer read and export paths must apply availability projection and remove old excerpts from their returned citation DTOs. Saved answer/report body text remains by design. Task 5 exposes the projection seam; it does not rewrite historical body text or create those separately owned subsystems.

## Runtime and testing

This branch's acceptance instance uses Compose project `researchdesk-sources` and `127.0.0.1:18081`. Existing generic scripts may default to `researchdesk`/18080; inspect them before use. `DocumentDeletionIT` and the new browser script target the isolated instance. Test results and integration boundaries are recorded in `PARALLEL-REPORT.md`.
