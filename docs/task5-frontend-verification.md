# Task 5 frontend verification

Date: 2026-10-12. Ownership: apps/web only. No dependency changes, no commits by the frontend subagent.

## Implemented

- Exported `SourceViewer({citation: Citation})` consumes canonical SourceInfo/Citation types and authorizes through GET /api/document-versions/{id}/source-info before displaying source or excerpt.
- Markdown uses inert React text with parser-compatible paragraph numbering, selected paragraph focus/scroll, no HTML or links executed.
- PDF uses authorized PNG page endpoint; bounded previous/next controls and reset to citation page. No native PDF plugin, scripts or untrusted links. Page content is not selectable text and no word-coordinate highlighting is claimed.
- Document page preview, close/Escape, confirmed deletion, cancellation, CSRF mutation, immediate preview closure and server-state refresh. Deletion notice explains history remains.
- Late source responses are ignored across version/auth changes. Deleted sources hide old excerpts; 410 returns deletion notice.

## Red / green evidence

- SourceViewer tests initially failed because component missing; first implementation passed 5 tests.
- DocumentPage new preview/delete and cancellation tests failed on absent buttons; implementation passed all 8 DocumentPage tests.
- PDF image/navigation test failed against iframe implementation, then passed image implementation.
- Same-document citation switch test exposed stale selected page, then passed after reset/refetch dependency fix.
- Final `npm --prefix apps/web test`: 3 files, 19 tests passed (SourceViewer 7, DocumentPage 8, auth race 4), 2026-10-12 run at 12:53 host log time.
- `npm --prefix apps/web run build`: TypeScript and Vite succeeded after feature changes; final repetition follows config-only unit/e2e separation.
- `git ... diff --check -- apps/web`: exit 0; only CRLF normalization warnings.

## Browser acceptance

`npm --prefix apps/web run test:e2e` uses isolated port 18081 and Edge via Playwright 1.64.0. Test creates unique disposable account/project, uploads Markdown/PDF, checks READY after reload, inert script text, keyboard paragraph focus, Escape, delete cancellation/confirmation, actual PNG decode/natural dimensions, page 2 and mobile overflow. Screenshots go to `.cache/task5-browser/`.

Final parent run on 2026-10-12 at 13:14–13:15 passed upload/reload/reopen/READY/Markdown inert text/keyboard focus and Escape/deletion cancellation and confirmation/PDF image decode and page navigation/mobile overflow. Three screenshots were visually inspected and retained under docs/test-evidence/task5. One accidental initial browser execution was picked up by Vitest and timed out against old web without preview controls; it created a fixture on 18081 only. Added `vitest.config.ts` to exclude standalone e2e from unit suite. This failure is not a successful browser acceptance claim.

No real AI provider calls or model evaluation performed. Generation/report consumers must import the exported SourceViewer and pass canonical Citation. Backend contract/source authorization and deletion lifecycle are verified by the backend owner.

## Independent review fix and runtime verification

- Reviewer found that keyboard opening left focus outside the preview panel. Regression test initially failed (1 failure / 8 passing DocumentPage tests). Added close-button focus on opening; 20 full frontend tests and the TypeScript/Vite build passed, and the reviewer confirmed the fix.
- Browser entry uses .mjs because this script is plain JavaScript and Node 22.17.1 cannot execute .ts by default. No dependency was added. The first invocation failed at the loader before browser interaction; the final mjs entry was tested.
- Browser reload returns to the existing project list; the acceptance reopens the project and verifies persisted source state. Paragraph selectors include numbered paragraph text. Earlier navigation/selector timeouts are recorded as unsuccessful attempts, not acceptance.
