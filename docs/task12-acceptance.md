# Task 12 acceptance ledger

Scope: independent delivery hardening on `parallel/delivery`, based on `474b9e5`. Full application acceptance must be rerun after Tasks 5–11 are integrated. No provider keys were requested and no real-model result is claimed.

## Acceptance gates

| Gate | Evidence / current dependency |
| --- | --- |
| Contracts, Java units, Python units, web units/build | Final Foundation run passed: Java 18 unit, Python 56 unit, web 10 unit plus build; contract results are included where applicable. Java integration passed 17 real Compose business cases and 2 standalone SpringBoot health cases. Full remains pending. See PARALLEL-REPORT.md. |
| Private resource ownership, CSRF, internal authentication | Existing real Compose Java integration suites, plus delivery regression tests. |
| Request correlation without sensitive payload logs | API request ID response/error consistency; edge/API access logs exclude paths, queries, bodies and authentication. |
| Worker crash and Redis outage | `apps/ai/tests/test_worker_recovery.py::test_sigkill_running_worker_and_redis_outage_preserve_durable_tasks`: hold a fixture-only index write, SIGKILL isolated worker, verify new fence and complete index; stop isolated Redis, verify durable task/outbox, restart and observe success. |
| Queue deletion, stale completion, partial index | Existing real worker and TaskLeaseIT cases, now restricted to explicit isolated Compose projects. |
| MinIO CVE-2025-62506 | Official fixed source build and reproducibility evidence in `docs/minio-build.md`. Successful build alone is not a complete current vulnerability audit. |
| Incomplete multipart cleanup | Actual accelerated 8s/1s fixture passed: expired part inaccessible, fresh part and completed bytes retained. Production uses 24h/1h on a dedicated instance. See docs/acceptance/multipart-2026-10-12.json and the listing-cache limitation in the MinIO guide. |
| Backup and recovery | Actual cold backup (27.77s) and empty-project restore through verification (26.55s) passed existing login, READY, original bytes hash, published index and new write. See docs/acceptance/cold-restore-2026-10-12.json. Report history/export recovery is pending integration. |
| Provider timeout, generation cancellation, deleted citations | Pending integration of generation/sources/report branches. Do not replace with ingestion assertions. |
| Per-user usage, unknown tokens, dated cost estimate | Pending generation persistence contract/integration; never report zero for unknown provider usage. |
| Full browser journey through report export | Pending Tasks 5–10 integration; foundation browser checks are not full smoke. |
| Real provider and holdout evaluation | Deferred by explicit user instruction until project implementation is complete. |

## Isolation incident

During initial red-test development on 2026-10-12, an isolation test called the legacy `compose('stop', 'ai')` helper before a subprocess interceptor was installed. The legacy helper ignored the requested project and briefly stopped the main `researchdesk-ai-1` container. It was immediately restarted using the main project's existing Compose definition; Docker reported it running afterward. The start operation also ran the existing migrate dependency. No main-repository source edits, volume deletions or manual SQL were issued. The dependency restart may perform its normal startup work; its data was not separately audited. This was an execution error, not an authorized fault experiment.

Subsequent guard tests intercept the subprocess and perform no Docker operations. Recovery helpers now require an explicit `researchdesk-delivery` or `researchdesk-test-*` project, reject reserved ports 18080–18085 for test projects, and check the actual proxy binding before any HTTP write or fault operation. Real destructive fault checks are only permitted in that isolated target. This incident must remain visible in the final report.
