# Integrated research journey

The merged application now connects the authorized upload/ingestion, research, source, report and export modules. The browser submits both ANSWER and REPORT to the existing `/api/runs` lifecycle. A completed REPORT is resolved through owner-scoped `GET /api/runs/{runId}/report` and opens that exact editable draft. An unavailable lookup offers an explicit retry, without creating another generation run.

`GenerationReportDraftSink` requires the existing final-completion transaction, reads owner, prompt, frozen version scope and mode from the locked run, and calls the injected proxied `ReportService.completeDraft`. ReportService's source-run idempotency, canonical citation validation and immutable versions remain authoritative. The adapter never performs network I/O and does not modify generation cancellation, sequencing or final source checks.

Research and report citations mount the authorized SourceViewer. The report renderer derives a selected citation from the current version's projected citations on every render, so deleted citation metadata cannot keep an old excerpt selected. SourceViewer reauthorizes the source before showing content. Model data use is disclosed beside the generation controls; the project mounts the usage panel once.

## Acceptance

`apps/web/tests/research-journey.e2e.mjs` uses the repository-pinned Playwright 1.64.0 with a fresh Edge profile. It only accepts a loopback `researchdesk-test-*` Compose project on ports18087–18999 and checks the actual proxy port binding. Default: `researchdesk-test-integration`, http://127.0.0.1:18087. It does not create fake report rows or seed retrieval results.

The journey covers registration → project → direct file upload → actual READY ingestion → scoped ANSWER → complete source viewer → actual REPORT generation → exact report lookup → version2 editing → immutable history → Markdown and PDF downloads/signatures → mobile390 → foreign-owner denial → source deletion → redacted answer/report citations → rejected stale export and deleted-task retry409. Evidence is saved under `.cache/integration-browser`. Run it after building and starting the isolated merged stack:

```
node apps/web/tests/research-journey.e2e.mjs
```

Focused red-green tests cover REPORT submission and opening, full-source mounting, current citation redaction, frozen run metadata mapping, owner-scoped report lookup, and returning from a generated draft to its list. Existing generation race and deadline suites remain separate; deterministic demo acceptance does not establish real-provider quality.
