# Reports independent review

Reviewed 2026-10-12. Fixed point: `474b9e5070ce94c63b925ff72d06948f087a3f8a`; committed reports head: `1c84a83`. Scope includes `git diff 474b9e5...HEAD`, the working-tree generated Python changes, and intended untracked Java report/export package, its tests, and V008/V009 migrations. Reviewed against `../COORDINATION.md`, Tasks 9/10 briefs, and this clone's approved design/plan. No feature code was changed by this reviewer.

**Verdict: request changes.** One P1 export recovery defect and one P2 deleted-source preview defect need fixes. Generation lifecycle integration remains an explicitly allowed pending dependency; the reports branch correctly supplies a completion seam rather than a competing run scheduler.

## Standards

Hard documented-rule breaches:

- **P1 — Export response body has no time limit.** [ExportAdapter.java:50](../apps/api/src/main/java/com/researchdesk/report/ExportAdapter.java) uses `ofInputStream()`, then synchronously calls `readNBytes()` at line 55. The request timeout covers receiving headers, but leaves body consumption unbounded. A renderer returning HTTP 200 and stalling its body blocks `ExportService.dispatch()` at line 162, preventing that dispatcher from reclaiming expired leases or processing subsequent exports. This breaches the recovery rule in approved design section 6. Apply a deadline to the entire response transfer and close the stream on expiration.

- **P2 — Updated deletion metadata retains a previously selected excerpt.** `ReportPreview.tsx:10` stores the selected `Citation` object. When refreshed props replace it with `DELETED`/`excerpt:null`, line 34 verifies only that its chunk ID remains present, then renders the old object's availability and excerpt. Opening a citation, deleting its source, and saving that report can therefore continue displaying the excerpt after the server has redacted it. This breaches design section 6: deleted historical sources must no longer expose excerpts. Store the selected chunk ID and resolve the citation from current props; suppress excerpts whenever availability is `DELETED`.

No separate Fowler smell warrants a finding. Report ownership, CSRF enforcement, immutable version inserts, CAS saves, and per-run completion serialization appear consistent with the documented rules.

## Spec

- **P1 — Durable export recovery can be blocked by a stalled body.** `apps/api/src/main/java/com/researchdesk/report/ExportAdapter.java:50-55`, called from `ExportService.java:162`. Task 10 requires a returned export task; the approved design requires recovery after worker interruption and bounded retries. A 200 response with headers followed by an indefinitely stalled or trickling body never reaches the timeout/retry handler. Its 120-second database lease expires, but the same dispatcher cannot resume scanning until this read returns. The byte count bounds memory, not elapsed time.

- **P2 — Preview displays a revoked citation snapshot.** `apps/web/src/features/reports/ReportPreview.tsx:10,18,34`. The design requires deleted sources to stop exposing excerpts while preserving historical report prose. Trigger: select an AVAILABLE citation, delete the source in another view, then save an edit that retains the marker. The save response correctly returns DELETED/null; the component keeps displaying the original selected object. In addition, `ReportEditor.test.tsx:44` explicitly expects a DELETED citation with a non-null excerpt to display that excerpt, which locks in behavior contrary to canonical redaction. Replace that assertion with unconditional DELETED excerpt suppression and a selected AVAILABLE -> DELETED/null rerender regression.

## Evidence and material gaps

A short loopback-only probe on the configured JDK 25 used the same `HttpClient`/`BodyHandlers.ofInputStream()`/`readNBytes()` pattern. After a warmup, a request timeout of 400 ms returned headers in 15 ms and completed its four-byte body read in 1,411 ms without a timeout. This establishes the P1 cause without changing application code or shared services.

The existing Java cases cover immutable/CAS saves, per-run idempotence, canonical citation scope, deleted response redaction, fixed historical exports, private owner-authorized downloads, and expired-lease recovery. The Python cases exercise Chinese embedded fonts, long tables, literal resource text, provider deadlines, and shared synchronous JSON fields. The frontend cases exercise explicit save, conflict copies, history and leave guards. They do not cover a response that stalls after headers, or selected-citation redaction after a prop update. Passing current suites does not cover these two failure paths; this reviewer did not rerun the root agent's suites.

The initially suspected export cleanup race is **not a release finding**: V002 defines `storage_cleanup.not_before` as one hour after insertion; normal export work is fenced by a 120-second lease. An advisory key guard could harden unusual prolonged writes, but the immediate-cleanup premise does not hold.

Internal renderer request length permits more than the public report length, but `_content()` independently rejects over 200,000 characters and the route requires service authentication. This mismatch does not establish a public authorization or resource-read defect.

Scope checks found no additional actionable P1/P2 defect in immutable history, ownership/CSRF, citation canonical project/version scope, deletion-aware download denial, completion run idempotence, private storage, or the resource-free Chinese PDF path. Real-provider quality evaluation remains deferred by user instruction and is not claimed by this review.

Summary: Standards 2 findings, worst P1 unbounded export body; Spec 2 findings, worst P1 blocked durable export recovery. Both axes identify the same two defects.
