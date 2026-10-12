# ResearchDesk

A separate research workspace built with React/TypeScript, Spring Boot, and an internal Python service. This initial foundation provides service health, dependency readiness, and shared contracts. Uploads, authentication, retrieval, generation, and reports arrive in later tasks. The frontend is explicitly a foundation preview; no model output is simulated or claimed as real.

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
./scripts/test-api.ps1 -Integration HealthIT    # Maven verify; passes -Dit.test
./scripts/test-contracts.ps1                   # regenerate, check drift, Python/TS/Java checks
./scripts/generate-contracts.ps1               # intentionally update generated types
apps/ai/.venv/Scripts/python.exe -m pytest apps/ai/tests
docker compose -f infra/compose.yaml config --quiet
docker compose -f infra/compose.yaml down       # preserve development data volumes
```

The Maven integration tests default to unavailable host dependencies (503). For a host-run API pointing to available PostgreSQL/Redis, set `EXPECTED_READINESS=200`. Container acceptance always verifies both available and unavailable states using real dependencies. The Maven wrapper and all caches are local to this repository. Dependency resolution requires network access on a clean machine; the Task 1 report records the Windows sandbox TLS workaround used here.

## Contracts and configuration

`contracts/openapi.json` is the canonical OpenAPI 3.1 definition. It specifies TaskState, RunState, RunKind, GenerationRequest, Citation, ApiError, InternalTask, and each discriminated RunEvent payload. TypeScript and Pydantic types are generated from it; Java DTOs share the exact JSON field names, with shared fixture round trips checked across languages. Unknown enum values fail validation. The drift check regenerates committed files before testing so stale generated types cannot silently pass.

Only Java will authorize user/project/document scope and forward AI traffic; the internal service has no host port or browser-facing proxy route. This foundation has no user-facing business endpoints, no database migrations, and no model integration. `.env.example` reserves provider and limit settings for later tasks. Do not put credentials into frontend builds. Database schema accounts, service identity, ownership checks, and stale-fence rejection are required in the subsequent implementation tasks.

This Compose baseline is development-only. It uses HTTP on loopback; production needs HTTPS and secrets. In particular, the pinned cached MinIO image predates the official CVE-2025-62506 fix and must be replaced with a reviewed patched build before production. No MinIO storage/admin ports are exposed to the host. See [dependency baseline](docs/dependency-baseline.md) for exact versions, digests, official sources, and limitations.
