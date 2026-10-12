# Initial parallel integration milestone

Date: 2026-10-12, Asia/Shanghai. Common integrated starting point: `213db9c`.

## Imported branches

The clean module histories were merged sequentially: sources (`7edd330`), retrieval (`ea5f4af`), generation (`21a3839`), reports (`2e4e2e2`), delivery (`213db9c`). All original reports are preserved under `docs/parallel/{sources,retrieval,generation,reports,delivery}-report.md`. The original retrieval milestone and its generation cherry-pick share content; merge conflicts retained the newer retrieval test environment and integration documentation rather than reverting to the imported older copy.

## Reconciliation

Merged additive OpenAPI paths and schemas, then regenerated TypeScript and Python contracts. Preserved source and research styles. Combined generation and export Python routers. Project navigation exposes documents, research and reports, preserving report dirty-leave protection. Error responses retain generation's explicit JSON content type and delivery's correlated request ID. Docker copies retrieval, generation (including bundled font), ingestion and worker once. Compose passes the same GENERATION_MODE to Java/Python, AI_BASE_URL to Java, and provider settings only to Python. User configuration remains deferred, default mode demo.

## Fresh verification

- `npm --prefix apps/web run build`: passed, TypeScript and Vite production build.
- `npm --prefix apps/web test`: 59 tests across 8 files passed.
- `scripts/test-api.ps1`, JDK25: 28 Java unit tests passed, no failures/errors/skips; BUILD SUCCESS. Log `.cache/integration-java-unit.log`.
- Contract generator executed successfully during both additive schema reconciliations.
- Conflict marker scan found none. No Docker service or volume operations were performed by this integration worker.

These checks establish a compiling integration baseline, not full application acceptance. Python full suite, repeated contract drift checks, integrated containers and end-to-end acceptance remain for the final harness. No real provider or holdout evaluation was run.

## Remaining seams assigned by coordinator

1. Await Python `generation.report.generate_report(evidence, provider, prompt, allowed_version_ids, titles, deadline)` correctly from report runs; adapt Java ReportDraftSink to ReportService.completeDraft with owner, title, frozen versions and demo metadata, within existing fenced final-completion transaction.
2. Connect SourceViewer in research and report citations and report completion navigation; keep deletion availability refresh and dirty editor guards.
3. Implement honest actual/unknown provider usage, model metadata, timing and dated configurable cost estimates, with persistence and UI. Unknown is not zero.
4. Configure final harness suites to `researchdesk-test-integration`, port18087 and isolated volumes. Existing source/generation/report tests have module-specific defaults and must be explicitly overridden, not accidentally pointed at stopped module fixtures. Add full uploaded-source through report/export journey and report/export backup restoration.
5. Package merged Python dependencies/wheels and build all containers, then run final contract/unit/security/recovery/browser acceptance under controlled resource load without relaxing parser/PDF safety budgets.

Other workers began from `213db9c`; this milestone report changes documentation only. No push/publication was performed.
