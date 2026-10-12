# Deploying and recovering ResearchDesk

This integrated delivery uses a dedicated disposable acceptance runtime. See `docs/integration-acceptance.md` for current executed evidence; real OpenAI-compatible provider execution is deferred by the user. Demo embeddings are deterministic fixtures, not semantic-quality evidence.

## Isolated Windows setup

Use PowerShell 7, JDK 25, Node 22.12+, Python 3.12 and Linux-container Docker. From this clone root:

```powershell
Copy-Item .env.example .env
$env:JAVA_HOME='C:/Program Files/Microsoft/jdk-25.0.4.7-hotspot'
./scripts/setup.ps1
./scripts/build.ps1 -Project researchdesk-test-integration -Port 18087
. ./scripts/runtime.ps1
Initialize-Runtime -Project researchdesk-test-integration -Port 18087
Invoke-DeliveryCompose up -d --no-build
./scripts/verify.ps1 -Scope Full -Project researchdesk-test-integration -Port 18087
```

Open http://127.0.0.1:18087. Build, verify and smoke default to this integration project. The delivery runtime remains available with explicit `-Project researchdesk-delivery -Port 18085`; the full integration fixtures require the integration project. Runtime scripts explicitly select `researchdesk-delivery` or `researchdesk-test-*`. Ports 18080–18084 and the shared `researchdesk` project are rejected. Do not run bare Compose commands; Compose defaults may select another project. The integration-only override exposes PostgreSQL at `127.0.0.1:15487` for report/retrieval fixtures; the delivery configuration keeps it internal. Redis, storage and Python remain network-internal. The API is accessed only through the proxy.

`verify.ps1 -Scope Unit` runs contracts, Java/Python/frontend unit checks and frontend build without Docker. `Foundation` also runs real Java/Python integration and failure recovery, dependency outage probes, and the upload browser journey. These tests stop isolated services temporarily; use disposable data. `Full` is the default and requires `apps/web/tests/research-journey.e2e.mjs`; it also checks controlled generation races, real deadline UI and report/export backup recovery. That journey must assert registration, upload/READY, answer citations, source navigation, immutable report edits, and authenticated Markdown/PDF export. A Foundation pass does not certify Full. `smoke.ps1` uses the same explicit scopes on an already running stack. Tests use fresh fixture accounts and only clean their own user/email counters. After input validation, registration allows 100 attempts per IP per 15-minute fixed window; login allows 100 attempts per IP and five per email. Successful login clears that email counter; the IP counter remains until its window resets. Invalid input rejected before the counters is not charged to them. Never clear live IP counters to make acceptance pass.

Set `RESEARCHDESK_PYTHON` to an existing interpreter only when its installed locked dependencies match this clone; tools never install into that interpreter. Normal setup creates this clone's own venv. Browser checks require installed Microsoft Edge; use `E2E_BROWSER_CHANNEL` only with a compatible installed browser. CI runs Unit on hosted Windows; real Docker/browser acceptance must run locally on an isolated Linux-container daemon.

## Configuration and deployment boundaries

Keep `.env` private. Replace development database, object-storage and internal-service secrets with unique random secrets before deploying beyond loopback. Terminate TLS at a trusted reverse proxy and set `SESSION_COOKIE_SECURE=true`; do not expose internal services or accept client-supplied internal authentication headers. Keep model keys server-side. Follow `.env.example` for provider mode and limits; the integrated OpenAI-compatible generation adapter reads server-side configuration. Runtime helpers explicitly load the repository-root .env when it exists, independent of the calling directory; process environment variables take precedence. Never interpret demo output as real-provider acceptance or omit provider errors by falling back to demo.

Record the exact commit and image digests used. A fixed MinIO build and multipart expiry must pass their dedicated checks before making a storage readiness claim. Earlier Task 1–4 reports describe a vulnerable development image and are historical evidence. The integrated acceptance report is the source of current tested status; docs alone do not certify a deployment. Maven/JDK may emit upstream Unsafe deprecation warnings; expected dependency-down traces during failure probes are not application acceptance failures, but unexpected errors must remain visible.

## Consistent backup and restore

```powershell
./scripts/backup.ps1 -Action Backup -Name before-upgrade -Project researchdesk-test-integration -Port 18087
./scripts/backup.ps1 -Action Restore -Name before-upgrade -Project researchdesk-test-restore -Port 18086
. ./scripts/runtime.ps1
Initialize-Runtime -Project researchdesk-test-restore -Port 18086
Invoke-DeliveryCompose up -d --no-build
./scripts/test-health.ps1 -ApiUrl http://127.0.0.1:18086
```

Backup stops only the chosen project's services, archives PostgreSQL, MinIO and Redis named volumes, writes SHA-256 hashes/UTC time/checkout HEAD (informational, not build provenance) and actual container immutable image IDs, then restarts only previously running services even after an error. Expect downtime. Backup rejects any service whose running/container image differs from the current configuration tag, preventing a rebuilt tag from mislabeling an older deployment. The manifest is written last, so incomplete backups cannot restore. Archives contain private source content, sessions and account hashes: restrict local ACLs, encrypt off-host storage, define retention and test restores. The script does not export `.env`; retain matching secrets separately in a private secret manager. Never commit backups or credentials.

Restore verifies hashes and matching immutable PostgreSQL/MinIO/API/AI/web image IDs, then creates volumes only for a new `researchdesk-test-*` project. It refuses any existing target containers or volume names. It does not upgrade database versions. Treat archives as trusted administrator input. A failed restore leaves only the new isolated volumes for inspection; it never deletes anything automatically. Reconcile the original secrets and commit before starting restored services. Verify an existing account login, a pre-backup source's exact bytes and READY state, immutable report history and exact Markdown/PDF export checksums. Test new writes and a controlled recovery afterward. Record measured recovery time and data timestamp; no RTO/RPO is claimed until measured.

This is a cold volume backup, not a live `pg_dump` plus unrelated object-store copy. Redis is retained for consistency but durable PostgreSQL task/outbox state remains the recovery authority. Do not delete queues or live throttle counters as deployment maintenance. Before manually removing a disposable test project, inspect `Invoke-DeliveryCompose ps` and `docker volume ls --filter label=com.docker.compose.project=<exact-project>` and confirm every name belongs to that test. These scripts never run broad prune or `down -v`.

Production overlay: terminate TLS directly with the supplied TLS proxy configuration; use bucket-scoped STORAGE_ACCESS_KEY/STORAGE_SECRET_KEY pre-created by the storage administrator, distinct from MinIO root credentials. See the infra production overlay for required certificate mounts and variables. Provision the private bucket and its restricted runtime account before starting API/AI. No script prints these secrets. Production profile validation is separate from local HTTP acceptance.

For the provided direct TLS ingress, Docker Compose >=2.24.4 is required. Put `fullchain.pem` and `privkey.pem` in the private `TLS_CERT_DIRECTORY`; the directory is mounted read-only. After loading the required private environment variables:

```powershell
. ./scripts/runtime.ps1
Initialize-Runtime -Project researchdesk-delivery -Port 18085
Invoke-DeliveryCompose -f ./infra/compose.production.yaml -f ./infra/compose.tls.yaml config --quiet
Invoke-DeliveryCompose -f ./infra/compose.production.yaml -f ./infra/compose.tls.yaml up -d --no-build
```

This overlay replaces HTTP publication with loopback TLS port 18485 by default (`RESEARCHDESK_TLS_PORT`). Configure a certificate valid for the chosen hostname and access that hostname over HTTPS. TLS terminates in the proxy that sets trusted request metadata. The HTTP Foundation harness is for the local development profile; a TLS deployment requires separate certificate/cookie/CSRF checks and must not disable certificate validation to claim a pass. Public DNS/ingress and publishing are outside this branch's local acceptance.

The build script prepares and SHA-256 verifies the pinned MinIO source archive before Docker packaging. Normal builds need official source/Go module network access; `infra/minio/stage-dependencies.py` stages module artifacts for restricted-network builders, which must still pass `go mod verify`. See the infrastructure acceptance evidence for the actual offline build command and result.

## Daily local demo

Keep ordinary demo data separate from failure-injection fixtures. After building the same source artifacts, select the dedicated delivery runtime explicitly:

```powershell
./scripts/build.ps1 -Project researchdesk-delivery -Port 18085
. ./scripts/runtime.ps1
Initialize-Runtime -Project researchdesk-delivery -Port 18085
Invoke-DeliveryCompose up -d --no-build
```

Open http://127.0.0.1:18085. Full verification targets the separate integration environment at18087; do not run its direct-database fixtures against the daily demo. Each project owns its volumes. Both projects share local image tags, so rebuilding does not automatically upgrade an already-running stack: recreate its services deliberately when ready.

The current index is `deterministic-demo-sha256-v1`,32dimensions,`sections-v1`. This is not semantic embeddings. Changing to real semantic vectors needs a versioned migration and reindex plan. `GENERATION_MODE=openai-compatible` enables the real generation adapter only; it does not transform existing demo vectors. Actual provider configuration, semantic-quality evaluation and the formal holdout remain deferred.
