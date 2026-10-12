# ResearchDesk

A separate research workspace built with React/TypeScript, Spring Boot, and an internal Python service. The workspace now provides email-form account registration, login, and private projects, plus service health and shared contracts. Uploads, retrieval, generation, and reports arrive in later tasks. No model output is simulated or claimed as real.

## Run locally (Windows / PowerShell 7)

Prerequisites: Git, JDK 21–25, Node 20.19+ or 22.12+, Python 3.11–3.13, and Docker with Linux containers. Set `JAVA_HOME` to the intended JDK; the tested machine's existing value still pointed to JDK 17 even though `java` on PATH was JDK 25. Only the project proxy binds a host port, on loopback by default.

```powershell
# From the repository root; choose your installed JDK location.
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-25.0.4.7-hotspot'
Copy-Item .env.example .env
./scripts/setup.ps1
./scripts/build.ps1
docker compose --env-file .env -f infra/compose.yaml up -d --no-build
./scripts/test-compose.ps1
```

Open `http://localhost:18080`. Java liveness is `GET /api/health` and readiness is `GET /api/ready`. Internal AI liveness/readiness are `/health` and `/ready` on port 8000 inside the Compose network. Liveness returns `200 {"status":"UP"}` during dependency outages; readiness returns 503 if the actual database or Redis probe fails. `test-compose.ps1` stops and restores this project's Redis and PostgreSQL separately to verify those behaviors; run it on a development instance.

The Dockerfiles package tested host artifacts and the downloaded, hash-checked Linux wheels; run `build.ps1` before `compose build`. The AI wheel target is Linux amd64 / CPython 3.12 / musl; another architecture requires a corresponding wheel target. No unrelated project service or database is used.

```powershell
./scripts/test-api.ps1 -Test ContractsTest       # Maven test; passes -Dtest
./scripts/test-api.ps1 -Test HealthIT -Integration    # Maven verify; passes -Dit.test
./scripts/test-contracts.ps1                   # regenerate, check drift, Python/TS/Java checks
./scripts/generate-contracts.ps1               # intentionally update generated types
apps/ai/.venv/Scripts/python.exe -m pytest apps/ai/tests
docker compose -f infra/compose.yaml config --quiet
docker compose -f infra/compose.yaml down       # preserve development data volumes
```

The Maven integration tests default to unavailable host dependencies (503). For a host-run API pointing to available PostgreSQL/Redis, set `EXPECTED_READINESS=200`. Container acceptance always verifies both available and unavailable states using real dependencies. The Maven wrapper and all caches are local to this repository. Dependency resolution requires network access on a clean machine; the Task 1 report records the Windows sandbox TLS workaround used here.

## Contracts and configuration

`contracts/openapi.json` is the canonical OpenAPI 3.1 definition. It specifies TaskState, RunState, RunKind, GenerationRequest, Citation, ApiError, InternalTask, and each discriminated RunEvent payload. TypeScript and Pydantic types are generated from it; Java DTOs share the exact JSON field names, with shared fixture round trips checked across languages. Unknown enum values fail validation. The drift check regenerates committed files before testing so stale generated types cannot silently pass.

Only Java will authorize user/project/document scope and forward AI traffic; the internal service has no host port or browser-facing proxy route. Auth and project endpoints are defined in the canonical contract. The migration-only Compose job applies versioned Flyway migrations before Java starts; the running API has only business-schema privileges and Python has only retrieval-schema privileges. There is no model integration. `.env.example` reserves provider and limit settings for later tasks. Do not put credentials into frontend builds. Project ownership is checked by Java using both resource and session-user IDs. Service identity and stale-fence rejection arrive in subsequent tasks.

This Compose baseline is development-only. It uses HTTP on loopback; production needs HTTPS and secrets. In particular, the pinned cached MinIO image predates the official CVE-2025-62506 fix and must be replaced with a reviewed patched build before production. No MinIO storage/admin ports are exposed to the host. See [dependency baseline](docs/dependency-baseline.md) for exact versions, digests, official sources, and limitations.

## Accounts and private projects

Open http://localhost:18080 and register with an email-form username and a password of at least 12 characters (at most 72 UTF-8 bytes). There is no email verification or password recovery. Projects are private to the account. Logout revokes the server session; expired/revoked sessions clear private frontend state and return to login.

Server sessions expire after 24 hours by default, use a random HttpOnly SameSite=Lax cookie, and store only its SHA256 digest in PostgreSQL. Write operations use the per-session X-CSRF-Token returned from auth/session. Registration/login before authentication reject cross-origin browser requests; when a valid session exists they also require its CSRF token. Login rotates both cookie and CSRF token. BCrypt cost12 hashes passwords. Five failed logins per email in a 15-minute window are allowed; the sixth is429. The edge overwrites X-Real-IP for the additional IP limits (100 login/registration attempts per15minutes).

Fresh PostgreSQL volumes provision the separate login roles automatically. For an existing Task1 volume, recreate only this project's PostgreSQL service to mount the provisioning files, then provision before starting the migration/API:

```powershell
docker compose -f infra/compose.yaml up -d --no-build postgres
./scripts/provision-database.ps1
docker compose -f infra/compose.yaml up -d --no-build
./scripts/test-api.ps1 -Test ProjectOwnershipIT -Integration
```

ProjectOwnershipIT is explicit Compose acceptance, uses unique fixture accounts, and cleans only those accounts/projects/sessions. It fails if the API/database are unavailable. It needs access to this project's Docker daemon for PostgreSQL fixture setup/cleanup; it does not expose a database port. Unit/host health checks continue to run separately. Set SESSION_COOKIE_SECURE=true behind production HTTPS and replace all development database passwords. API and AI runtime containers never receive the PostgreSQL administrator password; only the exited migration job uses it.
