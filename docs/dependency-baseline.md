# Dependency baseline — 2026-10-12

## Runtime compatibility and selection

| Component | Locked selection / observed runtime | Reason and official compatibility source |
| --- | --- | --- |
| Git | Host 2.53.0.windows.3 | Independent repository on `feat/researchdesk-v1`; no application coupling. |
| Java / Spring Boot | Host Microsoft JDK 25.0.4; compile release 21; container Temurin 21.0.12+8; Boot 3.5.16 | [Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html) accept Java 17–25 and Maven 3.6.3+. Release 21 allows the same artifact on the cached LTS runtime. Boot parent/BOM manages Spring, Jackson, JDBC, Redis, and test versions. |
| Maven | 3.9.11; wrapper 3.3.4 | [Official wrapper](https://maven.apache.org/tools/wrapper/) pins the distribution; official SHA512 verified, SHA256 stored in wrapper properties. The generated Windows wrapper has one defensive null-Target check for Windows PowerShell 5.1; its upstream indexing failed on a regular directory. |
| Node / UI | Host Node 22.17.1/npm 10.9.2; React/DOM 19.2.4; Vite 7.3.7; TypeScript 5.9.3 | [Vite requirements](https://vite.dev/guide/) and the selected package engine require Node `^20.19.0 || >=22.12.0`, which the tested host meets. React is the approved UI architecture; no server framework is needed for this starter. `npm install` audit and production build passed. |
| Python / AI | Host 3.11.4; container 3.12.14; FastAPI 0.135.4; Uvicorn 0.42.0; psycopg 3.3.3; redis client 7.3.0 | [FastAPI first steps](https://fastapi.tiangolo.com/tutorial/first-steps/) support Python 3.10+. Project range is 3.11–3.13. [FastAPI version guidance](https://fastapi.tiangolo.com/deployment/versions/) recommends pinning a tested release. Windows tests and Linux container startup/probes both passed. |
| Contract tooling | openapi-typescript 7.13.0; datamodel-code-generator 0.53.0; jsonschema 4.26.0; Pydantic 2.14.1 | Types generated from OpenAPI 3.1, validated with shared fixtures and unknown-enum/payload rejection. [openapi-typescript](https://openapi-ts.dev/introduction), [datamodel-code-generator](https://koxudaxi.github.io/datamodel-code-generator/). |
| PostgreSQL / vectors | PostgreSQL 17.11; pgvector 0.8.6 image | PG17 remains [supported through 2029](https://www.postgresql.org/support/versioning/). [pgvector documentation](https://github.com/pgvector/pgvector) supports PostgreSQL 13+ and provides PG17 image tags. No schema/extension migration is performed in Task 1. |
| Redis | Server 7.4.2 | Real PING is checked by Java Actuator and Python. [Redis PING](https://redis.io/docs/latest/commands/ping/) documents the probe behavior. |
| MinIO | RELEASE.2025-04-22T22-12-26Z; Go1.24.2 | Available cached official image, strictly development-only. See production blocker below. |
| Proxy / Docker | nginx 1.29.1; Docker 29.8.0 / Compose 5.5.1 | Same-origin `/api/` forwarding, web fallback; Python and infrastructure stay private. [nginx proxy module](https://nginx.org/en/docs/http/ngx_http_proxy_module.html). |

Liveness excludes external dependencies; readiness explicitly includes the real database and Redis because this project's required operations need both. This follows the distinction in the [Spring Boot probe documentation](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html). Java uses bounded Hikari/Redis timeouts and Python uses bounded psycopg/Redis timeouts; an outage produces 503 without turning liveness into a failure.

## Locks and immutable images

`apps/web/package-lock.json` locks direct/transitive npm packages with integrity hashes. `apps/ai/uv.lock` locks production and test packages and hashes; `requirements.lock` exports production dependencies for hash-checked offline container installation. The `uv` tool used to resolve/export is pinned to 0.13.0 in setup. Maven pins Boot's parent/BOM, wrapper/distribution, and inherited plugin versions; `apps/api/dependencies.lock` is a sorted audit snapshot of resolved coordinates (not a Maven-enforced lockfile).

These manifest digests were read from the local Docker daemon and are used directly in Compose/Dockerfiles. Pulling a floating tag is never required to reproduce the selected content.

| Image | SHA256 manifest digest |
| --- | --- |
| pgvector/pgvector:0.8.6-pg17-trixie | `724a4041afdb1750446e3f6b5cfa8f3b0ac5a2cf538ddfa6bfee4f94c2fa85c6` |
| redis:7.4.2-alpine | `02419de7eddf55aa5bcf49efb74e88fa8d931b4d77c07eff8a6b2144472b6952` |
| quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z | `a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e` |
| eclipse-temurin:21.0.12_8-jre-alpine-3.24 | `1a29e1fe337eb28b5bec30f0ee8ed29f0ff80ab6f75dcf9313efe82911065a52` |
| python:3.12-alpine (observed 3.12.14) | `b64631e04e4920160c50fbe8d8df828f7f35f06f425cb44aa09bca53e708a35a` |
| nginx:1.29.1-alpine | `42a516af16b852e33b7682d5ef8acbd5d13fe08fecadc7ed98605ba5e3b26ab8` |

## Development and production limits

The cached MinIO release predates [the official 2025-10-15 security release](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z), which fixes service-account/STS session-policy privilege escalation (CVE-2025-62506). The official release directs container users to build from source; the repository is now archived. A patched, reviewed object-store build is an explicit production blocker, to be resolved in deployment readiness. This baseline exposes neither MinIO API nor console ports, carries no user data, and is only a local development foundation. It must not be promoted unchanged to production.

During verification, Docker Hub registry DNS failed. Already cached, digest-pinned official images allowed all container builds and runtime acceptance to finish. Fresh machines still need registry access or an approved image mirror/cache. Windows PowerShell/curl sandbox TLS reported `SEC_E_NO_CREDENTIALS`; Python urllib successfully downloaded official Maven content and verified its checksum. The local wrapper cache was seeded from that checked archive. Vite's Windows subprocess required execution outside the restricted sandbox; its production build then passed. Neither global runtime configuration nor other project files/services were changed.

No live model evaluation was run: Task 1 only establishes contracts/health. Provider configuration is deferred by user instruction until project completion. The environment file includes placeholders without secrets; no browser build receives them.
