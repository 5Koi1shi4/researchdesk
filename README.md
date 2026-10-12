> Task 12 isolated delivery uses `researchdesk-delivery` on port 18085. See [deployment and backup/restore](docs/deployment.md), [demo](docs/demo.md), and [interview notes](docs/interview.md). Run `scripts/verify.ps1 -Scope Foundation` for the available foundation. Default Full verification fails closed until the integrated report/export journey exists. Real-model evaluation is deferred by the user; no quality score is claimed.

# ResearchDesk

A separate research workspace built with React/TypeScript, Spring Boot, and an internal Python service. The workspace now provides email-form account registration, login, and private projects, plus service health and shared contracts. Private PDF/Markdown uploads now run through recoverable extraction and a published PostgreSQL/pgvector index; retrieval, generation, and reports arrive in later tasks. No model output is simulated or claimed as real.

## Run locally (Windows / PowerShell 7)

Prerequisites: Git, JDK 21–25, Node 20.19+ or 22.12+, Python 3.11–3.13, and Docker with Linux containers. Set `JAVA_HOME` to the intended JDK; the tested machine's existing value still pointed to JDK 17 even though `java` on PATH was JDK 25. Only the project proxy binds a host port, on loopback by default.

```powershell
# From the repository root; choose your installed JDK location.
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-25.0.4.7-hotspot'
Copy-Item .env.example .env
./scripts/setup.ps1
./scripts/build.ps1
. ./scripts/runtime.ps1
Initialize-Runtime -Project researchdesk-delivery -Port 18085
Invoke-DeliveryCompose up -d --no-build
./scripts/test-compose.ps1
```

Open `http://127.0.0.1:18085`. Java liveness is `GET /api/health` and readiness is `GET /api/ready`. Internal AI liveness/readiness are `/health` and `/ready` on port 8000 inside the Compose network. Liveness returns `200 {"status":"UP"}` during dependency outages; readiness returns 503 if the actual database or Redis probe fails. `test-compose.ps1` stops and restores this project's Redis and PostgreSQL separately to verify those behaviors; run it on a development instance.

The Dockerfiles package tested host artifacts and the downloaded, hash-checked Linux wheels; run `build.ps1` before `compose build`. The AI wheel target is Linux amd64 / CPython 3.12 / musl; another architecture requires a corresponding wheel target. No unrelated project service or database is used.

```powershell
./scripts/test-api.ps1 -Test ContractsTest       # Maven test; passes -Dtest
./scripts/test-api.ps1 -Test HealthIT -Integration    # Maven verify; passes -Dit.test
./scripts/test-contracts.ps1                   # regenerate, check drift, Python/TS/Java checks
./scripts/generate-contracts.ps1               # intentionally update generated types
apps/ai/.venv/Scripts/python.exe -m pytest apps/ai/tests
Invoke-DeliveryCompose config --quiet
Invoke-DeliveryCompose down       # preserve development data volumes
```

The Maven integration tests default to unavailable host dependencies (503). For a host-run API pointing to available PostgreSQL/Redis, set `EXPECTED_READINESS=200`. Container acceptance always verifies both available and unavailable states using real dependencies. The Maven wrapper and all caches are local to this repository. Dependency resolution requires network access on a clean machine; the Task 1 report records the Windows sandbox TLS workaround used here.

## Contracts and configuration

`contracts/openapi.json` is the canonical OpenAPI 3.1 definition. It specifies TaskState, RunState, RunKind, GenerationRequest, Citation, ApiError, InternalTask, and each discriminated RunEvent payload. TypeScript and Pydantic types are generated from it; Java DTOs share the exact JSON field names, with shared fixture round trips checked across languages. Unknown enum values fail validation. The drift check regenerates committed files before testing so stale generated types cannot silently pass.

Only Java will authorize user/project/document scope and forward AI traffic; the internal service has no host port or browser-facing proxy route. Auth and project endpoints are defined in the canonical contract. The migration-only Compose job applies versioned Flyway migrations before Java starts; the running API has business-schema privileges and narrowly typed publication-guard functions; Python has retrieval-schema privileges and cannot change Java-owned attempt/fence/lease fields. There is no model integration. `.env.example` reserves provider and limit settings for later tasks. Do not put credentials into frontend builds. Project ownership is checked by Java using both resource and session-user IDs. Internal task callbacks require the shared INTERNAL_SERVICE_TOKEN, and Java rejects stale attempts, fences and expired leases. The development token in Compose must be replaced for production; it is never present in browser builds.

This Compose baseline is development-only. It uses HTTP on loopback; production needs HTTPS and secrets. Task 12 replaces the earlier vulnerable cached MinIO baseline with a pinned build of fixed official source; see the Task 12 acceptance report for actual build and runtime verification. No MinIO storage/admin ports are exposed to the host. See [dependency baseline](docs/dependency-baseline.md) for exact versions, digests, official sources, and limitations.

## Accounts and private projects

Open http://127.0.0.1:18085 and register with an email-form username and a password of at least 12 characters (at most 72 UTF-8 bytes). There is no email verification or password recovery. Projects are private to the account. Logout revokes the server session; expired/revoked sessions clear private frontend state and return to login.

Server sessions expire after 24 hours by default, use a random HttpOnly SameSite=Lax cookie, and store only its SHA256 digest in PostgreSQL. Write operations use the per-session X-CSRF-Token returned from auth/session. Registration/login before authentication reject cross-origin browser requests; when a valid session exists they also require its CSRF token. Login rotates both cookie and CSRF token. BCrypt cost12 hashes passwords. Five login attempts per email in a 15-minute window are allowed; the sixth is429. The edge overwrites X-Real-IP for the additional IP limits (100 login/registration attempts per15minutes).

Fresh PostgreSQL volumes provision the separate login roles automatically. For an existing Task1 volume, recreate only this project's PostgreSQL service to mount the provisioning files, then provision before starting the migration/API:

```powershell
Invoke-DeliveryCompose up -d --no-build postgres
./scripts/provision-database.ps1
Invoke-DeliveryCompose up -d --no-build
./scripts/test-api.ps1 -Test ProjectOwnershipIT -Integration
```

ProjectOwnershipIT is explicit Compose acceptance, uses unique fixture accounts, and cleans only those accounts/projects/sessions. It fails if the API/database are unavailable. It needs access to this project's Docker daemon for PostgreSQL fixture setup/cleanup; it does not expose a database port. Unit/host health checks continue to run separately. Set SESSION_COOKIE_SECURE=true behind production HTTPS and replace all development database passwords. API and AI runtime containers never receive the PostgreSQL administrator password; only the exited migration job uses it.

## Private document uploads

Open a project and select a PDF or UTF-8 Markdown file. The upload disclosure explains that source material will be used with the configured model service. Accepted sources begin PROCESSING with a QUEUED task. The internal worker claims a database lease, extracts text and publishes all validated chunks before Java marks the source READY. Transient failures retry at most three times after the initial attempt; exhausted transient failures show a controlled retry action, while permanent parsing errors require a corrected upload. Lists and task reads always authorize the session owner and omit internal object keys.

The default single-file limit is20MiB, PDF limit200pages, and project quota50documents. MAX_FILE_BYTES, MAX_PDF_PAGES, and MAX_DOCUMENTS_PER_PROJECT in .env may tighten these defaults; larger values fail startup. The proxy allows22MiB total multipart framing and returns a stable JSON413 for larger requests. Java applies the actual per-file bound while streaming to a temporary disk file. Markdown must decode strictly as UTF-8 and contain text. PDF admission rejects encryption, malformed files, excess pages and no extractable text; it performs a one-shot presence/metadata check in a separate JVM, limited to128MiB heap,96MiB metaspace,10seconds,2concurrent inspectors and32MiB total decoded streams. Full extraction runs in a separate Python process with256MiB memory,8CPU seconds on Linux,10seconds wall time,2concurrent parser slots,32MiB decoded streams and32MiB protocol-output bounds. Windows host checks use a Job Object memory bound. External file/network access is denied after trusted library/codec imports, and library logs cannot contaminate the result file. Markdown retains heading text and paragraph numbers; PDF chunks retain page numbers. These bounds can reject structurally complex sources.

Internal Java storage uses a private MinIO bucket on the existing Compose network; no storage/admin port or object URL is exposed to the browser. Storage writes precede the short project-row-locked transaction. That transaction creates a document, version, parsing task and Outbox together, with a project/digest unique constraint. Duplicate uploads reuse the persisted IDs and state, including at quota. A durable storage_cleanup intent exists before every external object write; successful promotion removes it in the same transaction, duplicate/rollback compensation deletes the object when possible, and failed compensation leaves the intent for cleanup. The Outbox dispatcher delivers to a Redis consumer group and rescans queued tasks every5seconds to recover a lost stream/group. Workers heartbeat a30second lease. Expired leases allocate a new attempt and fence. Retry backoff is2/4/8seconds plus0–500ms jitter. Unpublished/partial writes and old attempts cannot become READY. Cleanup reconciles due intents, retains them after transient MinIO errors and retries after10seconds. Upload promotion/compensation and cleanup share a per-object advisory lock; any referenced object is retained. Task 12 adds explicit aged incomplete-multipart reconciliation; verify its policy and tests before deployment.

```powershell
./scripts/test-api.ps1 -Test UploadIT -Integration
./scripts/test-ingestion.ps1       # real Redis/PostgreSQL/MinIO recovery acceptance
./scripts/test-upload-limits.ps1   # temporarily tests1KiB/2pages/2sources, then restores process settings and API
npm --prefix apps/web test
npm --prefix apps/web run test:e2e:documents
```

The browser acceptance uses locked Playwright1.64.0 and a fresh isolated headless Edge profile by default. Set E2E_BROWSER_CHANNEL to another installed Playwright-supported channel if necessary. It uploads only its own disposable fixtures, verifies actual PostgreSQL task/outbox state, checks upload errors and390px layout, and cleans its fixture account/project/objects. Screenshots are written under .cache/task3-browser/screenshots. Integration probes briefly stop/pause only this project's MinIO and always restore it; run them on the local development stack. The pinned MinIO security/production limitation above still applies.

The current embedding adapter is explicitly `deterministic-demo-sha256-v1` with32dimensions and index version `sections-v1`. It is a deterministic pipeline fixture and provides no real semantic/model quality claim. Model identity, dimensions and index version are persisted; an incompatible adapter is rejected before any write and requires a new index/reindex workflow. No provider key is needed.

Task 12 storage hardening and incomplete-multipart handling are documented in the delivery acceptance report; completed-object cleanup alone does not remove unfinished upload IDs.

`build.ps1` runs container-independent Python checks before packaging; after starting Compose, run `test-ingestion.ps1` for all ingestion integration checks. These tests deliberately stop and restore only ResearchDesk AI/MinIO and delete only its Redis ingestion stream plus disposable fixture records. PostgreSQL remains the task source of truth.
