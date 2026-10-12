# ResearchDesk integrated architecture review

Date: 2026-10-12. Reviewer: independent integration review agent.

## Scope and evidence

Reviewed the approved design and implementation plan, common milestone `213db9c`, integration handoff `e004aa8`, usage implementation `ef2a515`, experience implementation `321dd01`, and file-ending normalization through `33e0cd8`. Read the original five module reports to preserve their reviewed boundaries and stated limitations. This is a review of the new integration code plus selected critical existing seams, not a new exhaustive audit of every line in the five branches. The coordinator owns the harness changes and their separate review; concurrent uncommitted harness/generated-contract edits are excluded from this verdict.

Read-only code inspection covered Java generation lifecycle and worker; usage service/controller/migration and unit/HTTP tests; Python provider, answer, report routes and ingestion logging; generated report adapter/lookup/controller and existing ReportService; project navigation, research reconnect/cancel guards, report editor/source preview and UsagePanel; Compose credential/service boundaries; and TaskService retry gating. No code, index, HEAD, runtime, container, or data mutation was performed. Only this report was written. No new tests were run by this reviewer, as the coordinator is running isolated acceptance and there was no additional concrete runtime concern warranting a duplicate test run.

## Strengths

- Final report completion remains inside GenerationService's locked terminal transition. It rechecks the frozen READY scope while holding document/version share locks, invokes the mandatory-transaction report adapter, creates a canonical independently editable draft, persists the terminal event, and records usage in the same transaction. A sink failure rolls these writes back; a late completion after cancellation is rejected before draft creation.
- The adapter obtains owner, frozen versions and demo mode from the durable run. The report lookup joins the exact source run, project and owner and requires a completed REPORT run; the HTTP controller requires the session and disables caching. ReportService canonicalizes citations against authorized stored chunks and deduplicates source_run_id without replacing edited versions.
- Usage unknowns remain null, actual zero remains zero, mode is authoritative from the run, and cost requires exact model/provider matching, complete consistent counts and dated explicit prices. SQL placeholder order matches inserted columns; the immutable per-run primary key and terminal lock prevent double counting. Project ownership protects reads; public rows omit prompts, content, endpoints and credentials.
- Provider usage-only SSE packets are supported. Java removes internal usage before validating/persisting the public RunEvent payload; ingestion and generation metrics log identifiers, fixed status and numeric timing rather than document/prompt/provider secrets.
- Research reconnect and cancellation retain run-request, project/auth-generation and run-ID guards. Report opening uses the exact completed run instead of guessing the newest report. ReportPreview resolves a selection against current citation metadata; SourceViewer checks current source availability before fetching source content. Navigation retains dirty-editor guards.
- Shared deployment still exposes only the loopback proxy, keeps provider credentials in Python, uses distinct database roles and gives Java and Python the same generation mode. The existing TaskService retry path positively requires FAILED document and task states, preserving the deleted-source gate.

## Findings

### Critical

None found in the reviewed scope.

### Important — open verification/contract mismatch

**P2: internal terminal event tests still validate the metadata envelope as a public RunEvent.** `apps/ai/tests/test_generation_transport.py`, `test_internal_route_emits_canonical_ordered_events`, validates each entire NDJSON packet with the generated strict public RunEvent. `apps/ai/generation/routes.py` now adds a terminal top-level `usage` member. The coordinator's current full build reproduced the resulting extra-field validation failure (122 passed, 3 failed at that stage; the other two failures were missing PDF test dependencies and are being handled separately).

The transport implementation deliberately separates this metadata from the browser event in GenerationWorker, so the evidence does not establish a public metadata leak. It does establish an unresolved test/contract boundary and means the final suite is not currently green. Resolve it by representing or explicitly validating the internal envelope separately, validating the extracted public event with the unchanged strict public RunEvent, and testing that metadata never enters persisted/public SSE. Do not relax public additional-field validation merely to pass this test. A typed internal schema is a good durable implementation; an explicit shared internal parser with equally strict tests can also express the boundary. The usage worker is assigned the fix; this item awaits its commit and focused re-review.

### Minor

No additional actionable finding. Already documented limitations are not repeated as defects.

## Requirements sanity and limits

The inspected integration connects upload/source modules to scoped research and generated report editing/export rather than leaving the prior report/source adapter seams unused. Ownership, private storage access, terminal truth in PostgreSQL, citation availability projection, explicit demo labeling, immutable versions, cancellation/reconnect guards and unknown-safe usage remain represented in code and prior module evidence. The coordinator must still finish the actual uploaded-source journey, merged unit/contract checks, fault/race suites and report/export restoration against the rebuilt isolated runtime; prior module pass counts do not establish those integrated results.

Already-delivered browser content cannot be remotely recalled after deletion in another tab; source-module documentation explicitly states this limitation. Fresh source reads and server history/event projections enforce current deletion state. This review does not claim continuously pushed cross-tab revocation.

Real endpoint/model/key configuration and holdout evaluation are intentionally deferred by the user's approved amendment. DemoEmbedding32 and deterministic generation do not demonstrate real semantic quality, and this review makes no such claim. Those deferred runs are not blockers for the presently authorized demo implementation.

## Assessment

**Ready to merge: with the internal event-boundary fix and final harness verification.** No further important architecture or implementation defect was found in the inspected integration. This is a conditional code-review verdict, not a claim that the full application has passed acceptance. Follow up on the boundary-fix commit and append its scoped closure below; use the coordinator's separate acceptance report for executed results.

## Scoped re-review and closure — 6110e90

Reviewed the complete `6110e90` diff, including Python `InternalUsage` / `split_internal_event`, Java `InternalGenerationEvent`, their call sites and negative/serialization tests. The previous P2 is **closed**.

- Python validates the private extension separately, permits usage only on terminal events, rejects unknown metadata fields and invalid scalar counts, and still validates the extracted event with the original generated public RunEvent. A regression explicitly asserts that the unstripped terminal packet remains invalid as a public event.
- Java requires the four public fields, rejects unknown envelope fields, validates the terminal-only extension and expected metadata fields/types, and returns separate event/usage values. The worker passes only the validated public payload to GenerationService and usage separately to the terminal transaction. The serialization regression verifies exactly four public fields and no usage. Missing legacy metadata remains supported and is recorded as unknown.
- The fix does not relax the public contract, change report completion fencing, or introduce a new metadata persistence/public-output path. No additional important defect was found in this scoped review.

Verification attribution: the usage worker and coordinator report full Python non-integration results of **131 passed, 11 deselected**, focused Java results of **8 passed**, and targeted Python transport/usage results of **26 passed**. Those results are also recorded in `docs/integration-usage.md`. The reviewer inspected the tests and implementation but did not independently rerun them or represent them as full-runtime acceptance.

A limited truthfulness check of documentation commit `478ca4c` found that README, deployment, demo and interview materials distinguish implemented integration from final acceptance evidence, explicitly disclose the deterministic 32-dimensional embedding limitation, and preserve the approved deferral of real-provider/holdout evaluation. No unsupported claim of real AI quality was found. Harness command execution and final runtime status remain with the coordinator's separate harness review.

**Updated assessment: approved for the reviewed code scope; no important finding remains open.** Full rebuilt-runtime acceptance is still in progress and is a separate delivery gate. This closure supersedes the conditional code-review verdict above while preserving its original failure record.
