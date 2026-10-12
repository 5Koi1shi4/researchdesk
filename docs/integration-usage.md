# Usage and observability integration

Implemented 2026-10-12. No real provider credentials or paid requests used.

## Behavior

- The OpenAI-compatible adapter requests `stream_options.include_usage` and captures usage-only SSE packets with `choices: []`. Missing, null, negative, boolean, string, floating-point or unsafe-integer counts remain nullable unknown values, never invented zero.
- Answers and reports attach safe usage metadata to the internal terminal NDJSON envelope. Java removes the envelope before validating the unchanged public RunEvent. Browser SSE never contains this internal metadata.
- Terminal transitions insert one immutable `business.usage_records` row in the same transaction, keyed by run ID. Cancellation, timeout, transport failures and absent metadata produce unknown token/cost values. Replayed terminal transitions cannot double count. Runs completed before V010 are not backfilled with invented usage.
- `GET /api/projects/{projectId}/usage` checks project ownership and returns the latest 100 records. It contains no prompt, answer, report body, key, endpoint URL or document content.
- Costs require REAL mode, the openai-compatible provider, exact `USAGE_PRICE_MODEL`, all three valid token counts with consistent total, nonnegative explicit input/output prices per million, ISO configuration date and three-letter currency. Missing/malformed configuration means unknown. Cost, currency, date and rates are persisted as a snapshot; later configuration changes cannot rewrite history. Demo never claims real billing.
- Optional server settings: `USAGE_PRICE_MODEL`, `USAGE_INPUT_PRICE_PER_MILLION`, `USAGE_OUTPUT_PRICE_PER_MILLION`, `USAGE_PRICE_DATE` (`YYYY-MM-DD`), `USAGE_PRICE_CURRENCY` (e.g. `USD`). No built-in current prices are assumed.

## Metrics and privacy

- Generation: monotonic retrieval, provider first-token and total elapsed milliseconds, tied to runId; absent provider calls keep TTFT unknown. Terminal metadata is persisted. Java can calculate duration for failures with a known run start, and queue wait from durable start/deadline timestamps. Queued cancellations keep generation duration unknown.
- Ingestion: monotonic parsing, indexing and total processing milliseconds; logs include only taskId, attemptId, state and numeric timing. Unreached stages remain unknown. Java claim logs queue wait from task creation, including any retry delay (not a per-attempt queue metric).
- Python metrics use Uvicorn's configured logging hierarchy so INFO records appear in service logs. No content or credentials are logged.
- Cancelling an upstream request does not guarantee an upstream supplier stopped billing; absent final supplier data remains unknown.

## Verification executed

- `apps/ai/.venv/Scripts/python.exe -m pytest apps/ai/tests/test_usage.py apps/ai/tests/test_generation.py apps/ai/tests/test_reports.py -q`: 31 passed. Local HTTP provider fixtures cover valid/missing/null/malformed counts, include_usage, provider/model/mode and secret exclusion. Endpoint tests cover separate metadata and report usage. Worker log test checks no content leakage. One existing Starlette/httpx deprecation warning.
- JDK 25 with repository Maven wrapper, `-Dtest=UsageServiceTest,RunEventValidatorTest test`: 6 passed. Covers unknown/malformed tokens, actual zero, exact model binding, demo suppression, required price date, and completed payload strictness. Maven emits a dependency Unsafe deprecation warning.
- `npm --prefix apps/web test -- src/features/usage/UsagePanel.test.tsx`: 2 passed. Unknown tokens are explicit, actual zero is retained, demo cannot show real billing, estimates show configuration date.
- Regression tests were written and observed red before provider/terminal/ingestion implementation. New Java/UI interfaces initially failed compilation/import resolution before implementation.

## Integration handoff

`UsageIT` contains real HTTP/PostgreSQL checks for cancelled usage idempotency, owner isolation, actual no-evidence demo completion, and privacy. It inherits the existing RunRecoveryIT checks. Run against the coordinator's isolated integration runtime after rebuilding the API/AI and applying V010; not executed by this worker. No Docker fault injection was performed. Contract generation is coordinated centrally after all OpenAPI additions. ProjectPage mounting is owned by the experience worker.
