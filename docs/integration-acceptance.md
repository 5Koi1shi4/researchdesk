# Integrated acceptance ledger

**PASS — final integrated demo acceptance completed on 2026-10-12.** The actual command `scripts/verify.ps1 -Scope Full -Project researchdesk-test-integration -Port 18087` exited **0**. A second full browser journey on a new empty-volume installation also exited **0**. Real provider configuration/execution and semantic-model/holdout quality evaluation remain deferred by the user; these results do not certify them.

## Runtime and reproducible commands

The primary acceptance runtime is `researchdesk-test-integration`, HTTP `127.0.0.1:18087`, with a test-only PostgreSQL binding `127.0.0.1:15487`. Build/verify/smoke default to this project/port. The shared `researchdesk` runtime on `18080` and the four stopped module projects were not stopped or restarted by this acceptance work. No volumes were deleted.

Use JDK 25 at `C:/Program Files/Microsoft/jdk-25.0.4.7-hotspot`, the locked host Python environment and Node dependencies. The final executed sequence was:

```powershell
./scripts/generate-contracts.ps1
./scripts/build.ps1 -Project researchdesk-test-integration -Port 18087
. ./scripts/runtime.ps1
$env:GENERATION_MODE='demo'
Initialize-Runtime -Project researchdesk-test-integration -Port 18087
Invoke-DeliveryCompose up -d --no-build
Assert-DemoRuntime
./scripts/verify.ps1 -Scope Full -Project researchdesk-test-integration -Port 18087
```

Generation mode is checked against both actual running containers before acceptance HTTP writes; setting a host variable alone is insufficient. Runtime/fault/backup/recovery helpers explicitly load the root `.env` when present. A nonsecret temporary fixture verified root configuration loading from a nested working directory. Guards require an isolated project and matching actual loopback binding. The report direct-DB fixture additionally verifies the actual owned PostgreSQL binding. The full-journey missing-file gate remains intact.

## Executed results

| Gate | Result |
| --- | --- |
| Final build | Exit 0; API, AI and web images built. Fixed MinIO image reused (`sha256:75dfef14ff71524e798a8bfb7e938a9574e3df739d2d879d9817d9e92d14d026`). Host and Linux renderer dependencies include ReportLab/Pillow and bundled font. |
| Contract drift and fixtures | Regeneration/drift check, TypeScript fixtures, Python 31 contract cases and Java contract tests passed. Generated integration contracts committed as `e5b9ac0`. |
| Java units | 35 passed, 0 failures/errors/skips. Maven repeats these units during the separate integration invocations; 35 is not multiplied as unique coverage. |
| Python units and evaluation scoring | 151 passed, 11 integration cases deselected, 0 skipped. Includes 20 scoring-unit cases; no holdout run. |
| Web | 65 tests across 10 files passed; TypeScript/Vite production build passed. |
| Dependency recovery and logging | Redis and PostgreSQL stopped separately: liveness stayed UP, readiness changed 200→503→200; API and AI recovered. Sensitive URL/query log regression passed. |
| Ordinary Java integrations | 52 passed, 0 failures/errors/skips, including ProjectOwnership 6, DocumentDeletion 4, Upload 9, TaskLease 1, Export 5, ReportVersion 2, ExportWorkflow 3, ReportDraft 2, RunRecovery 7, GenerationScope 1, Usage 10, Health 2. Counts include inherited cases. |
| Upload limit override | 1 integration case passed; original API settings restored in finally. |
| Python real integrations | 11 passed, 131 nonintegration cases deselected, 0 skipped (106.26 s): 5 actual PostgreSQL retrieval cases plus 6 worker/recovery cases, including SIGKILL, Redis loss, durable retry/fence and storage cleanup. |
| Generation races | GenerationRaceIT 9 passed, 0 failures/errors/skips (includes 7 inherited recovery cases). Actual cancellation and source deletion reject late completion. Internal fault server is test-only; API/AI/proxy restored in finally. |
| Deadline browser | Real held generation expired through the persisted deadline; failed UI, error message, refresh-restored snapshot and editable composer passed. This is a separately labeled fault fixture, not a replacement for normal generation. |
| Full normal browser journey | Real registration→upload/READY→scoped ANSWER/demo badge→authorized original source→REPORT draft lookup→editable v2 and immutable v1→report source viewer→actual Markdown/PDF downloads passed. Also verified 390px layout, nonowner isolation, source deletion/redaction, old-export denial and retry409. |
| Cold backup/restore | PASS, backup `drill-afccbf640e3e`: 29.49 s backup, 26.84 s restore-to-verification. Existing login, exact source SHA-256, published index, new write, two immutable report versions and historical Markdown/PDF exact SHA-256 all passed. |
| Fresh empty-volume install | PASS, `researchdesk-test-fresh-f7e4c2e9` on `18089`; checked no containers/volumes existed before startup, reused the final images, ran the full browser journey, then stopped the project and preserved all three volumes. |

The ordinary 52-case suite, upload-limit 1-case suite and race 9-case suite are separate executed integration invocations; inherited tests are intentionally counted as executions, not as unique tests.

## Evidence and final state

- Build log: `.cache/integration-build-final.log` (exit 0); startup log: `.cache/integration-start.log`.
- Full run: `.cache/integration-verify-full.log` (exit 0).
- Main browser result/screenshots/downloads: `.cache/integration-browser-main/`; `result.json` identifies project `researchdesk-test-integration` and eight completed checkpoints.
- Deadline screenshot: `.cache/integration-browser-main/timeout.png`.
- Fresh installation log: `.cache/integration-fresh-smoke.log` (exit 0); browser evidence: `.cache/integration-browser-fresh/`.
- Cold restore summary: `.cache/drill-afccbf640e3e-result.json`; original backup and restored data volumes are preserved. Restore project `researchdesk-test-delivery-restore-drill-afccbf640e3e` is stopped.
- Parent and acceptance worker visually inspected desktop/mobile screenshots: editor, preview, references, export controls and explicit demo/unknown usage were legible; no horizontal clipping at 390px. The usage list is long but does not block interaction.
- Final primary integration readiness returned HTTP 200. Its services remain running for inspection. Fresh and restore projects are stopped; their volumes remain. No broad prune, `down -v`, or volume deletion was performed.

## First-attempt history and limits

The first build stopped at the Python gate (exit 1): 122 passed, 3 failed. Two PDF failures were a stale host venv lacking the merged locked ReportLab dependency; `uv sync --project apps/ai --locked` installed missing dependencies and all five export tests passed. The third exposed an internal terminal-event usage envelope being validated as a public RunEvent. Fix `6110e90` preserves strict public events, validates and strips internal usage metadata at the Java boundary, and adds serialization regression coverage. The final complete command passed after these corrections; no assertion was weakened and no parser/PDF safety budget was raised.

Deterministic 32-dimensional demo embeddings and vector retrieval verify the integrated authorization/lifecycle path, not semantic embedding quality or a holdout threshold. Local loopback provider stubs exercised real HTTP streaming and usage protocol handling; no external model endpoint was called. Unknown token counts/cost remain unknown. Real API configuration, real provider behavior/quality/latency/charges, and holdout evaluation are deferred. The measured cold-restore times describe this small fixture dataset and are not a production RTO/RPO promise. Production IAM, deployment credentials, public hosting, legacy MinIO-volume upgrades and a current complete vulnerability audit are outside this local acceptance.

Upstream JDK/Mockito dynamic-agent, PDFBox font-metadata and FastAPI TestClient deprecation warnings remain visible; they were not suppressed or treated as application failures. The earlier delivery-branch isolation incident remains documented in `docs/task12-acceptance.md`; it is not erased by this later isolated passing run.
