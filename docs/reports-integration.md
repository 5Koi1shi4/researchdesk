# Report module integration and acceptance

Tasks 9 and 10, branch `parallel/reports`, isolated from base `474b9e5`.

## Generation lifecycle wiring

The generation branch owns `/api/runs`, cancellation, fencing and terminal events. This report branch provides no second run scheduler. Its lifecycle must invoke the proxied Spring `ReportService.completeDraft(projectId, ownerId, runId, title, markdown, citations, allowedVersionIds, demo)` in the final completion transaction, after validating the run is still current. The method canonicalizes citations using the authorized project/version/chunk scope and locks source document rows until commit. The same run returns the same first version; another run creates a separate report, preserving human edits.

Python `await generation.report.generate_report(evidence, provider, prompt, allowed_version_ids, titles, deadline)` returns `{markdown, citations}`. The provider seam accepts the sibling synchronous `stream(messages, max_tokens, deadline)` adapter and async fixtures. Structured user messages contain `question`, `history: []`, and frozen `evidence` fields. No prior chat answer is evidence. Java needs the `demo` flag from provider/run metadata; the UI clearly labels demo generated versions. Real model configuration and evaluation remain deferred by user instruction.

All `[[cite:<chunk UUID>]]` occurrences are metadata, including occurrences in code blocks. Preview and exports number them by first occurrence. Saving removes unused citation mappings from the new version, and unknown or malformed markers are rejected.

## Export service wiring

Java calls `POST /internal/reports/export` at `AI_INTERNAL_URL` (default `http://ai:8000`), with `Authorization: Bearer INTERNAL_SERVICE_TOKEN` and `{markdown, citations, format}`. The token must match the AI environment. Python returns bytes without accessing a database or accepting object/file paths. Java alone selects the immutable version, owns durable export tasks, stores private attachments and authorizes downloads.

The sources branch should keep its deletion transition on the same `business.documents` rows. Public report citation responses immediately hide deleted excerpts. Previously rendered attachments containing available-source excerpts return `409 EXPORT_SOURCE_CHANGED` after deletion; the user can export the same version again with a deleted-source appendix. Report body text remains as specified.

## Isolated acceptance runtime

Use PowerShell from this clone. Do not execute existing unrelated acceptance suites against their default18080 port.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-25.0.4.7-hotspot'
$env:RESEARCHDESK_PORT = '18084'
$env:ACCEPTANCE_URL = 'http://localhost:18084'
$env:REPORTS_BASE_URL = 'http://localhost:18084'
docker compose -p researchdesk-reports -f infra/compose.yaml -f tests/reports.compose.yaml up -d --build
./scripts/test-api.ps1 -Test 'ReportVersionIT,ReportDraftIT,ExportIT' -Integration
./apps/ai/.venv/Scripts/python.exe -m pytest apps/ai/tests -m 'not integration' -q
npm --prefix apps/web test
npm --prefix apps/web run build
node apps/web/tests/reports.e2e.mjs
./scripts/test-contracts.ps1
```

Before the commands above, build the Java JAR and web dist as required by the existing Dockerfiles. Install Python development dependencies from the locked project before contract checks. The PostgreSQL test override exposes only loopback15484 for the service transaction tests. Production Compose does not need this override. The report Compose project owns its named volumes; no broad stop/prune/volume deletion is part of acceptance.

The inherited Compose uses a development MinIO image. Delivery owns the hardened image and final deployment scripts. This branch makes no production-readiness claim. Full uploaded-source → retrieval → generated run → report completion acceptance remains pending merged feature branches; the manual report/editor/export path and Java generation completion seam are separately tested here.
