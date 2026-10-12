# Python report and export verification

## Implemented seams

`await generation.report.generate_report(evidence, provider, prompt, allowed_version_ids, titles=None, deadline=None)` returns `{markdown, citations}` using the frozen Evidence attributes (`chunk_id`, `document_version_id`, `text`, `page`, `paragraph`, `score`). It accepts the generation branch synchronous `provider.stream(messages, max_tokens=6000, deadline=...)` and asynchronous test providers. Structured messages contain `question`, `history: []`, and evidence fields expected by the shared provider. No evidence means no provider call. Unknown/malformed citations, evidence outside the authorized versions, empty output and output over 200,000 characters fail. The default deadline is 300 seconds; an async caller timeout also bounds a stalled synchronous producer. The configured provider remains responsible for cooperative socket cancellation. Java owns run lifecycle and final persistence.

All literal `[[cite:chunk_id]]` occurrences are citation metadata, including code. Numbering follows first occurrence, and unused mappings are omitted. Exports use canonical Citation; DELETED entries never export their excerpt. Source state is supplied by Java, not trusted from browser input.

`POST /internal/reports/export` accepts `{markdown, citations, format: MARKDOWN|PDF}` with `Authorization: Bearer INTERNAL_SERVICE_TOKEN`. Empty/mismatched token configuration fails closed. The renderer accepts no object key or file path and returns bytes. Java selects the immutable version, authorizes ownership, owns durable tasks and stores private attachments.

## Rendering boundary and visual evidence

ReportLab 4.4.9 renders escaped text, bounded tables and split cells. It has no HTML, image, stylesheet or resource resolver. HTTP URLs, file URLs, raw HTML and Markdown images remain literal text. Only the bundled font is read for rendering; no system font is required. PDF includes a subset embedded TrueType Chinese font.

`docs/export-evidence/render_sample.py` produced the committed six-page Chinese PDF with a 99-row table, long citation and deleted-source citation. Pages 1, 2 and 6 are committed as PNG evidence. Pages 1 and 6 were independently inspected by root: Chinese text is readable, table borders fit the margins, long references wrap and the deleted-source notice omits the secret excerpt. Parser/font tests also check Chinese text extraction, more than two pages, FontFile2 embedding and oversized table-cell splitting. These checks establish renderer behavior; they do not establish model quality.

## Font provenance

Downloaded 2026-10-12 from `https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/Variable/TTF/Subset/NotoSansSC-VF.ttf`; OFL license is stored at `generation/fonts/OFL.txt`, from `https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/LICENSE`.

Original SHA256: `D68BAFCB48A2707749396AA12BBBD833CB70401F3A9A689FD2902C7E0D295964`.

Frozen regular `wght=400` instance SHA256: `F3CC47217191067100906EA2BFDC467F8E3E1465CAF835A8A4A8CFCADBC1381B`.

The regular instance was created with fonttools 4.61.1 as verification tooling. Production requires no fonttools. Upstream font metadata retains the Thin name; the frozen outlines use weight 400.

## Actual tests and limits

Initial report/export test collection failed because the implementation modules did not exist. The synchronous provider compatibility test later failed with `TypeError` before the shared interface alignment. Final root command:

```powershell
./apps/ai/.venv/Scripts/python.exe -m pytest apps/ai/tests -m 'not integration' -q
```

Result: **65 passed, 5 deselected, 1 upstream Starlette TestClient deprecation warning**, 30.80 seconds. The 5 deselected baseline tests depend on external ingestion integration and were not part of this report renderer run. Targeted report/export tests cover provider deadlines (async and sync), canonical citations, invalid evidence/markers, Chinese multipage rendering, deleted redaction and internal authentication.

Generation lifecycle wiring and full uploaded-source → retrieval → generated run → saved report acceptance remain pending integrated branches. Real model configuration and evaluation are deferred by the user; no provider key was requested and no real-model result is claimed. See `reports-integration.md` and the final `PARALLEL-REPORT.md` for integrated module results.
