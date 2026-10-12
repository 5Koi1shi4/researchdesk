# Report browser verification

Run from this isolated reports checkout after the reports API/web containers have been rebuilt:

```powershell
$env:REPORTS_BASE_URL='http://localhost:18084'
node apps/web/tests/reports.e2e.mjs
```

The pinned Playwright dependency starts Edge with a fresh profile under this clone's `.cache/reports-browser`, never a shared browser profile. The script rejects another port and does not invoke Docker or touch a database. It creates a new fixture account/project/report through the browser and leaves those isolated fixtures available for inspection.

Checks: real registration and report creation; explicit save; immutable empty version 1; read-only history; raw HTML and remote images inert; no external preview requests; route leave prompt; exporting saved version 2 while edits remain unsaved; actual Markdown download content; real optimistic conflict caused by a second authenticated request; copy retains the draft; 390px no horizontal overflow. Screenshots and downloaded Markdown are in `.cache/reports-browser`.

Component tests also verify repeated citations, dynamic numbering after marker removal, deleted-source excerpt suppression (including AVAILABLE to DELETED/null refresh and malformed non-null excerpts), current excerpt refresh, selected-panel removal, stable citation markers inside code, CSRF/baseVersion save fields and beforeunload. Every `[[cite:...]]` marker is metadata, including markers inside code, matching the backend/export interpretation. Preview supports headings, emphasis, code, tables, unordered lists and quotations; links/images/HTML stay literal.

PDF rendering/visual evidence, source-grounded demo draft insertion, export access-control and deleted-source checks are verified by the report Python/Java owners. Real provider quality remains deferred until configuration is supplied; these browser checks do not establish model quality.

Final runtime browser rerun on 2026-10-12 after the reviewed fixes: **PASS**. It exercised the rebuilt API, AI and web containers under researchdesk-reports18084; output is .cache/reports-final-browser.log. Committed screenshots are docs/reports-evidence/desktop.png, mobile.png and conflict.png. The independent review found two issues (unbounded renderer body and stale selected citation); both are closed, with dedicated body/size and deletion-prop regressions. Deleted citations never expose excerpts; report prose remains preserved.
