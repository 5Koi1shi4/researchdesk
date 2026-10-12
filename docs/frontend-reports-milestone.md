# Frontend report milestone

Implemented Task9/10 frontend in `apps/web/src/features/reports`: report list/manual empty draft creation; Markdown editing and inert reading preview; stable citation numbering and deleted-source snapshots; explicit optimistic save; conflict preserves draft and copies to a new report; immutable history; beforeunload and project-navigation guard; saved-version Markdown/PDF export job polling and authenticated download links. Minimal integration is in ProjectPage; generated OpenAPI types are consumed without extra dependencies.

UI uses the approved navy/canvas/white/gold palette, Georgia/Songti report titles, 16px text, labels, 44px controls, focus rings and 390px responsive stacked panels. Preview supports basic Markdown and keeps resource-bearing links/images/HTML literal. Every stable marker is citation metadata, including code, matching Java/Python. Citation snapshots are shown directly; the sources branch's full live SourceViewer remains an integration seam.

Observed red: four behavior tests failed against empty component stubs; report list test failed against empty list stub. Additional Markdown structure regression failed before implementation. Final evidence: `npm test` passed 4 files / 19 tests; `npm run build` passed TypeScript and Vite (38 modules, JS216.05kB, gzip68.49kB). Pinned `npm ci --no-audit --no-fund` installed 129 packages only in this clone. No dependency/lockfile changes.

Browser script: `node apps/web/tests/reports.e2e.mjs` uses isolated Edge profile and reports18084. Not yet executed: parent is rebuilding API/Python/web. It covers real create/save/history/safe preview/no external requests/dirty route prompt/version2 download/real409 conflict-copy/mobile screenshot. See `docs/report-browser-verification.md`.

Pending: independent review, real isolated browser execution, PDF backend and visual verification by Python owner, source-grounded generated draft integration. Real provider quality remains deferred by user. No imported sibling commits.
