# Reports implementation ledger

Plan: docs/superpowers/plans/2026-10-12-researchdesk-implementation.md, Tasks 9 and 10.
Base: 474b9e5070ce94c63b925ff72d06948f087a3f8a. Branch: parallel/reports.
Only this clone is writable. No imported commits.

- Java report/export persistence, authorization, V008/V009 migrations: java_reports agent, in progress.
- Python report generation seam and secure PDF/Markdown renderer: python_reports agent, in progress.
- Report editor, history, conflicts and export controls: web_reports agent, in progress.
- Root: isolated runtime researchdesk-reports on 127.0.0.1:18084; namespace contracts; integrated verification; independent review; final report.
- Shared baseline services started (postgres/redis/minio), volumes scoped researchdesk-reports.
- Real provider configuration and evaluation are deferred by user instruction. Demo checks are not model quality evidence.

## Recovery and verification

- Contract milestone b07f91e; frontend05f83e5; Python36c6707,bd1aa93,cfe8dd2,1c84a83. No sibling commits imported.
- Root baseline ready18084. Separate PostgreSQL loopback15484 via reports-test override. Scope always researchdesk-reports.
- Java first red ReportVersionIT expected201/actual404 (.cache/reports-java-red.log).
- Root initial Java build11 unit tests passed; first real ReportVersionIT+ReportDraftIT+ExportIT9 tests,1 failed SQL fixture JSON quoting (.cache/reports-java-it.log). Active java_finish fixes SQL helper to stdin, retry2/4/8 and strict full-body render deadline.
- Python final local65 passed,5 external integration tests deselected (.cache/reports-python-tests.log). Provider synchronous JSON seam aligned1c84a83; actual provider config deferred.
- Contract repeat generation stable after newline normalization:31 Python checks +7 Java checks and TypeScript contract compiler (.cache/reports-contracts-final.log).
- Browser script actual PASS against18084: create/save/history/HTML inert/no remote requests/saved-version Markdown download/conflict-preserved/copy/mobile390 (.cache/reports-browser.log). Desktop/mobile screenshots visually inspected.
- Six-page Chinese PDF evidence inspected: docs/export-evidence/page-1.png and page-6.png; text/font/multipage tests pass.
- Independent review recovered after initial reviewer quota failure. reviewer review_recovered found selected stale citation excerpt privacy regression (UI fix pending) and render body read without full deadline (Java fix in progress). Cleanup-race finding withdrawn after V002 one-hour delay was verified.
- Root owns contracts/generated types, docs/reports-integration.md and tests/reports.compose.yaml. java_finish owns Java final fixes. A UI fix worker will own ReportPreview/tests/docs after review finishes.
- Root final remaining: independent review fixes/review; rebuild final API+AI+web; run final Java9+regression IT/component/build/contracts/browser; copy browser evidence; write PARALLEL-REPORT.md; commit and clean status. Keep branch/clone per authorization; never main merge/push.

## Final module milestone

- Java code committed fba76c380b699bc13a3a40d3d589f7156880e3f7; UI deletion fix edb777f7fe6aed66617289c1ee20271538022065.
- Independent rereview approved; P1/P2 closed, no important issue open.
- Final rebuilt runtime verification:13 Java unit+12 IT,0/0/0 at13:34:04; browser PASS. Python65 pass/5 deselected, web22 pass/build, contracts31 Python+7 Java/TS pass.
- Actual screenshots copied to docs/reports-evidence. PARALLEL-REPORT.md contains scope, SHAs, test commands/results, imports none, dependency and risk notes.
- Keep branch/clone for integration. Whole-project generated lifecycle/SourceViewer acceptance remains pending sibling integration; real provider config/evaluation explicitly deferred.
