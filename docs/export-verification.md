# Python report and export verification

Owned implementation: generation/report.py, generation/export.py, generation/export_routes.py, app router registration, Docker generation/font COPY, dependency locks.

Report seam is async generate_report(evidence, provider, prompt, allowed_version_ids, titles=None, deadline=None). Evidence uses frozen chunk_id/document_version_id/text/page/paragraph/score attributes. Provider.stream(messages,max_tokens=6000,deadline=monotonic_deadline) is an async iterable of text. No provider call occurs for insufficient evidence. Unknown or malformed citations and unauthorized evidence fail. Output is capped at 200,000 characters and 300 seconds; caller owns run lifecycle/persistence. No sibling generation import or competing scheduler.

All literal [[cite:chunk_id]] occurrences are citation metadata, including fenced/inline code. Editing removes unused citations from export appendix; numbers are recomputed by first occurrence. Canonical Citation is used; DELETED entries never export their excerpt.

POST /internal/reports/export accepts {markdown,citations,format:MARKDOWN|PDF}, authenticated with Authorization: Bearer INTERNAL_SERVICE_TOKEN. Empty/mismatched configuration fails closed. Java selects the immutable version, verifies ownership, creates task and stores private bytes. Python returns bytes directly. This internal route is not browser accessible.

ReportLab 4.4.9 uses escaped text paragraphs and bounded/splittable tables. It has no resource resolver: raw HTML, Markdown images, file URLs and HTTP URLs remain literal text, never fetch targets. Only bundled font is read by rendering. No system font dependency. Oversized table cells split over pages. PDF contains subset embedded TrueType Chinese font.

Red evidence: test_reports.py collection failed with missing generation.report; test_export.py collection failed with missing generation.export before implementation. Green: apps/ai/.venv/Scripts/python.exe -m pytest apps/ai/tests/test_reports.py apps/ai/tests/test_export.py -q => 9 passed (one upstream Starlette TestClient deprecation warning).

Actual rendering: docs/export-evidence/render_sample.py creates Chinese heading, 99-row table, long Chinese citation, deleted citation; six pages. PNG pages 1, 2 and 6 saved, page 1 and 6 inspected visually: readable Chinese, clear table grid, citation wraps within page margins, deleted-source message present with no secret excerpt. PDF pypdf extraction and embedded FontFile2 validated by tests. These are deterministic renderer checks, not model quality evaluation. No real provider configured or evaluated.

Bundled font source: https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/Variable/TTF/Subset/NotoSansSC-VF.ttf . OFL license: https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/LICENSE (stored fonts/OFL.txt). Downloaded 2026-10-12. Original SHA256 D68BAFCB48A2707749396AA12BBBD833CB70401F3A9A689FD2902C7E0D295964. Frozen wght=400 regular instance via fonttools 4.61.1 (verification tooling only).

Pending integration: generation sibling must call report seam with its committed provider/retrieval interfaces; Java immutable history/ownership and browser journey verified by their owners. Isolated Docker acceptance is orchestrator-owned. No production readiness or real-model score claimed.

Final frozen font SHA256: F3CC47217191067100906EA2BFDC467F8E3E1465CAF835A8A4A8CFCADBC1381B. Regular instance visual recheck on page 2 confirms repeated header and readable row 30–61 without clipping. Renderer metadata retains upstream Thin name; actual frozen outlines use wght400. PDF production uses no fonttools runtime dependency.

Latest verification 2026-10-12: same targeted pytest command => 13 passed, one upstream deprecation warning, 4.80s. Includes stalled provider stream deadline, malformed/no citations and 200000 character bound. Commits 36c6707 and bd1aa93. asyncio.timeout enforces provider stream wall deadline independently of provider implementation.
