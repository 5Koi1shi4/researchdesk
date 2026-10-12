# Generation integration contract (Tasks 7 + 8)

This branch owns `/api/runs`, conversations, final completion persistence and streaming. The canonical OpenAPI is `contracts/openapi.json`; no consumer should create another run scheduler.

## Python boundary

`POST /internal/generation/stream`, bearer `INTERNAL_SERVICE_TOKEN`, accepts the Java-frozen `{runId, kind, projectId, documentVersionIds, prompt, history:[{role,content}], titles:{documentVersionId:title}}`. Response is NDJSON canonical RunEvent. Python validates evidence against the frozen version set, does not derive permissions from prompt/history, and verifies stable `[[cite:<chunk_id>]]` markers before completion. Java allocates persisted sequence numbers and verifies final source availability.

`GENERATION_MODE=demo|openai-compatible`; demo is explicit in every RunSnapshot (`mode: DEMO`). Real provider credentials stay in the Python environment. Missing real configuration fails; it never falls back to demo. Actual real provider acceptance is deferred by the user.

Retrieval is imported from `retrieval.retrieve(project_id, allowed_version_ids, query, limit=8)`, consuming `Evidence(chunk_id,document_version_id,text,page,paragraph,score)`. No duplicate retrieval implementation belongs in this branch.

## Report generation seam

Reports own `generation/report.py`. Its observed interface is `async generate_report(evidence, provider, prompt, allowed_version_ids, titles=None, deadline=None) -> {markdown, citations}`. The generation endpoint lazily adapts this result to canonical delta/citation/completed events. Until the report implementation is integrated, REPORT fails explicitly with REPORT_NOT_AVAILABLE.

Java's report completion seam is recorded below after implementation review. It must execute inside the same transaction as final source validation and COMPLETED persistence, create a new draft, and never overwrite human edits.

## Browser boundary

POST `/api/runs` returns 202 RunSnapshot. GET `/api/runs/{id}` and POST `/api/runs/{id}/cancel` return the same snapshot. Snapshot fields: runId,conversationId,projectId,kind,documentVersionIds,prompt,state,text,citations,lastSeq,mode,error. Mode is DEMO|REAL; error is nullable ApiError.

GET `/api/runs/{id}/events` uses Last-Event-ID. SSE `id` is a monotonic integer and data is canonical RunEvent. Reconnect replays data and never submits a new generation request. HTTP410 EVENT_WINDOW_EXPIRED requires loading the complete snapshot. Cancellation is terminal even when a provider sends late output, and provider billing may continue.

GET `/api/projects/{projectId}/conversations` and GET `/api/conversations/{id}` expose only owner-scoped runs. History is frozen at creation, limited to12 messages then conservatively capped at8000 tokens. Historical assistant text is context, not evidence.

Source preview is supplied by the sources module via the ResearchPage source-rendering seam; snapshot excerpts remain subject to source availability. Canonical citations use AVAILABLE|DELETED, with deleted excerpts redacted.

## Isolated acceptance

Only Compose project `researchdesk-generation`, proxy127.0.0.1:18083, clone-local build artifacts and dedicated volumes may be used. The original baseline has shared project defaults, so acceptance must always pass an explicit project and port. No real-model score is implied by deterministic demo tests.

## Java report draft persistence

Implement a Spring bean of `com.researchdesk.research.ReportDraftSink` with `saveDraft(String runId, String projectId, String markdown, List<Contracts.Citation> citations)`. It is called inside the final completion transaction after source rows are locked and revalidated. Use the run ID as an idempotency key. Do not call an external service inside this callback. With no sink installed, REPORT output remains a run snapshot and no editable report entity is created; the reports branch must supply the bean before report end-to-end acceptance.

## Verification runner and fault phase

`scripts/test-generation.ps1 -Phase unit|module|http|race|browser` propagates failures. `module` runs generation/contract Python checks, all web tests and generation/contract Java unit tests. `unit` additionally runs all existing Python/Java tests; failures are never automatically converted to module success. `unit` requires the clone Python environment (or `-Python <interpreter>`), Node dependencies and JDK. `http` and `browser` require the dedicated Compose service on18083. The Java fixtures hardcode Compose project researchdesk-generation to prevent modifying the main project's data.

The race phase uses `apps/api/src/test/fixtures/generation_fault_server.py`, copied to `/tmp` and launched inside only researchdesk-generation-ai-1 on internal port8001. Set only the isolated API's AI_BASE_URL to http://ai:8001, recreate its proxy, wait for readiness, then run `-Phase race`. The fixture holds a streamed draft until the test releases completion; it tests real database transaction/cancellation behavior, not model quality. Restore AI_BASE_URL=http://ai:8000 afterwards. The fixture is excluded from production images.

When recreating a service with the baseline proxy configuration, also recreate the dedicated proxy so its upstream addresses are refreshed. Generation Docker acceptance uses clone-local temporary overrides; final Compose environment wiring belongs to the delivery integration.

Events are committed immediately with the snapshot in one transaction, rather than delayed batch flushes. This favors crash consistency; no throughput claim is made. The event reader batches replay up to256 records and retains24 hours of events.

Set GENERATION_MODE identically on Java and Python: Java freezes the displayed mode in the run snapshot; Python chooses the provider. Forward OPENAI_BASE_URL, OPENAI_MODEL and OPENAI_API_KEY only to Python. The delivery branch must wire these server environment variables into final Compose configuration.
