# Reports parallel delivery — Tasks 9 + 10

Date: 2026-10-12, Asia/Shanghai. Status: **report module implemented, committed, independently reviewed and verified; ready for integration**. Full ResearchDesk acceptance and real-model evaluation remain pending.

Workspace: `E:\test\work\researchdesk-parallel\reports`. Branch: `parallel/reports`. Base: `474b9e5070ce94c63b925ff72d06948f087a3f8a`. Feature code milestone: `fba76c380b699bc13a3a40d3d589f7156880e3f7`. No sibling commits were imported. Only this assigned clone was changed; no main-repository merge, remote push or publication was performed.

## Scope delivered

- Java report/export packages and V008/V009 migrations: immutable saved versions, owner/CSRF enforcement, same-base optimistic conflict409, conflict copy, canonical project/version/chunk citations, removal of unused mappings, deleted-source excerpt redaction, per-run idempotent completion seam and a separate draft for each new run.
- Python report seam against shared Evidence/provider fields, synchronous JSON provider compatibility, evidence/citation validation, output/deadline bounds and insufficient-evidence behavior. No competing `/api/runs` scheduler or copied provider adapter.
- Editor with safe Markdown preview, explicit saves, beforeunload/navigation guard, history read-only view, conflict preservation/copy, dynamic citation numbering and clearly labelled demo generated versions. Deleted citation selection always resolves current metadata and never exposes an excerpt.
- Fixed-version Markdown/PDF export tasks, private object storage and owner-authorized attachment downloads. Durable leased/fenced recovery, four total attempts with2/4/8-second transient retry delays, permanent-error termination,20MiB response cap and complete-response deadline up to90 seconds. Attempt-specific keys plus object cleanup synchronization prevent stale publication/cleanup interference.
- Resource-free PDF text renderer with bundled OFL Chinese font, multipage tables, long citations, deleted-source appendix and real rendered evidence. URL/image/HTML text is not resolved into network or arbitrary local reads.
- Namespaced OpenAPI endpoints and generated TypeScript/Python contracts, with the canonical Citation reused.

## Commits

| SHA | Milestone |
| --- | --- |
| `b07f91e` | Report/export contracts and contract tests |
| `05f83e5` | Report editor, preview, history, conflicts and export controls |
| `36c6707` | Grounded report seam and Chinese PDF renderer/font/evidence |
| `bd1aa93` | Provider stream deadline regression/fix |
| `cfe8dd2` | Provider deadline verification record |
| `1c84a83` | Shared synchronous provider JSON alignment |
| `25500dd` | Integration seams, isolated test config and initial independent review |
| `edb777f7fe6aed66617289c1ee20271538022065` | Revoked/stale selected citation redaction fix |
| `fba76c380b699bc13a3a40d3d589f7156880e3f7` | Java immutable reports, private durable exports and recovery/deadline regressions |

The final documentation/evidence commit contains this report; its SHA is provided in the chat delivery.

## Actual verification

Runtime is isolated: Compose project `researchdesk-reports`, proxy bound to loopback18084, PostgreSQL test override bound to loopback15484. Redis, MinIO and Python have no host public bindings. Final API/AI/web images were rebuilt and deployed, and proxy DNS was refreshed before runtime rechecks. All volume/service operations named this project; no broad stop/prune or volume deletion ran.

| Command/check | Actual result |
| --- | --- |
| `./scripts/test-api.ps1 -Test 'ReportVersionIT,ReportDraftIT,ExportIT,ExportWorkflowIT' -Integration` | Final rebuilt-runtime run at13:34:04 +08:00: **13 unit tests +12 integration test executions**,0 failures/errors/skips; BUILD SUCCESS,45.848s. Log `.cache/reports-final-java.log`. |
| `./apps/ai/.venv/Scripts/python.exe -m pytest apps/ai/tests -m 'not integration' -q` | **65 passed,5 deselected**,30.80s. One upstream Starlette TestClient deprecation warning. The5 deselected baseline ingestion integration cases were not executed in this renderer run. Log `.cache/reports-python-tests.log`. |
| `npm --prefix apps/web test` | After reviewed UI fix: **22 tests,4 files passed**. Includes selected AVAILABLE→DELETED/null, direct DELETED/non-null, current excerpt replacement and marker removal. |
| `npm --prefix apps/web run build` | TypeScript and Vite production build passed after final UI fix. |
| `./scripts/test-contracts.ps1` | Repeat generation stable after LF normalization; **31 Python checks +7 Java checks**, TypeScript contract compiler passed. Log `.cache/reports-contracts-final.log`. |
| `node apps/web/tests/reports.e2e.mjs` with `REPORTS_BASE_URL=http://localhost:18084` | Final rebuilt-runtime **PASS**: registration, project/report creation, explicit save, immutablev1/history, no script execution/remote preview requests, dirty leave guard, savedv2 Markdown download excluding unsaved edits, real409 conflict/copy,390px no horizontal overflow. Log `.cache/reports-final-browser.log`. |
| Chinese PDF render/parser/font/visual checks | Committed six-page PDF and pages1/2/6 PNGs. Root inspected pages1/6; readable Chinese, clear table grid, wrapped long references and deleted-source notice without excerpt. Embedded FontFile2 and Chinese extraction verified. |
| Compose config and Git whitespace | Isolated override passed `docker compose ... config --quiet`; final `git diff --check` checked before documentation commit. |

Java integration uses real PostgreSQL, HTTP, internal renderer and MinIO where appropriate. Workflow fault tests replace only external adapters/storage to deterministically exercise retry and lock boundaries while asserting real persisted database state; these do not replace actual renderer/storage acceptance. Test counts are executed JUnit cases, including inherited HTTP tests. No mock/model output is presented as an AI quality score.

Red evidence included the missing report endpoint (expected201/actual404), missing Python modules, provider sync/async mismatch, stale selected excerpt, unbounded body response, fixed-delay recovery and missing object-key lock. The Windows psql JSON-argument fixture failure was corrected by UTF-8 stdin. Initial own Python environment lacked dev dependencies; locked development dependencies were installed only in this clone. The main venv/cache were reused read-only. Temporary automatic approval/model quota failures were resolved after the user's continuation; they did not become fabricated test success.

## Independent review

`docs/reports-independent-review.md` records the two initial findings and final scoped closure:

1. P1: renderer200 headers followed by a stalled body could block recovery. Fixed with a limited body subscriber and a deadline covering the entire response, with cancellation. Real loopback body-stall/oversize tests passed2/2.
2. P2: selected citation retained an old excerpt after redaction. Fixed by selecting by chunk ID and resolving current metadata, plus unconditional DELETED suppression. Three regressions failed before implementation and passed afterward.

Final verdict: **approved; no important finding open**. Additional real-PostgreSQL workflow regressions passed3/3. The review retained its original evidence, verified the fixes and found no important regression.

## Pending integration and risk notes

- Generation lifecycle owner must await `generation.report.generate_report(...)` and invoke the proxied Spring `ReportService.completeDraft(...)` in its fenced final-completion transaction. It must supply the frozen authorized versions, canonical candidates and explicit demo metadata. This branch deliberately supplies the seam; it does not own `/api/runs` or wire a second scheduler.
- Source deletion integration must preserve the shared `business.documents` availability transition/locks. History body text remains; deleted excerpts are hidden. Old available-source exports are denied with409 and can be regenerated with a deleted-source appendix. Full SourceViewer navigation and the uploaded-source→retrieval→generation→report journey await integrated source/retrieval/generation branches.
- The report acceptance helpers intentionally default to Compose project researchdesk-reports and database15484. The final integration harness must configure or adapt them to its intended fixture environment; this branch does not claim that running those defaults tests the merged main application.
- Real OpenAI-compatible base URL/model/key configuration is deferred until project completion by user instruction. No key was requested, no real provider was called, and no real quality/holdout result is claimed.
- The inherited Compose MinIO image is development-only; delivery owns its hardened replacement and deployment packaging. No production-readiness claim is made here. The PostgreSQL loopback test override is not a production configuration.
- JDK test tooling emitted Mockito self-attachment warnings; PDFBox warned about upstream font metadata retaining the Thin name. The embedded font outlines are frozen atwght400, hash checked; neither warning caused a test failure.
- Runtime containers/volumes and isolated browser fixture accounts are preserved for inspection. The branch/clone is kept for the integration chat; no shared workspace was archived.

## Evidence and handoff files

- `docs/reports-integration.md`: exact lifecycle/export API seams and isolated acceptance commands.
- `docs/java-reports-implementation.md`: Java transaction/fence/private-storage behavior and targeted red/green evidence.
- `docs/export-verification.md`, `docs/export-evidence/`: renderer boundary, OFL/font hashes and actual PDF/PNGs.
- `docs/report-browser-verification.md`, `docs/reports-evidence/`: actual desktop/mobile/conflict evidence.
- `docs/reports-independent-review.md`: final independent verdict and closure evidence.
- `tests/reports.compose.yaml`: loopback-only isolated PostgreSQL test override.
