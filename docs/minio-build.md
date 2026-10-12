# MinIO source build and storage boundary

The previous April 2025 binary is removed. The replacement is built from the official
[security release](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z),
commit `9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a`, fixing CVE-2025-62506.
Source archive SHA-256: `45521908307306e925c98d629e1c17d78c8b72b6ee242b1bfb1409f7d8ee5841`.
The Dockerfile pins compiler/runtime image digests, checks the source checksum,
verifies Go modules, retains the AGPL license, and runs as UID 10001 without capabilities.
It does not contain `mc`; use the S3 SDK or a separately reviewed administration client.

From repository root:

```powershell
./infra/minio/prepare.ps1
docker compose -p researchdesk-delivery -f infra/compose.yaml build minio
```

If the container cannot resolve the Go module proxy but the host can:

```powershell
python infra/minio/stage-dependencies.py
docker build --build-arg GOPROXY=file:///opt/go-proxy --build-arg GOSUMDB=off -t researchdesk-delivery-minio:2025-10-15-fixed infra/minio
```

The offline path downloads only official proxy artifacts selected by the pinned
source's `go.sum`. Disabling the online checksum service here does not disable
`go.sum` integrity checks or `go mod verify`. The cache and archive are ignored;
prepare them before a fresh build. This is a pinned build recipe, not a claim of
bit-identical image digests across platforms. The source is unmodified; provide
its corresponding source and license when redistributing the image.

The [upstream repository](https://github.com/minio/minio) was archived on April 25, 2026. This removes the known
CVE blocker; it does not establish continued security maintenance or blanket
production readiness. Review supported storage alternatives and ongoing scans
before a public deployment. The Go toolchain is pinned to the release toolchain,
not claimed to be the newest security-patched Go version.

## Incomplete multipart uploads

Only this application's private buckets belong on this dedicated MinIO instance.
`MINIO_API_STALE_UPLOADS_EXPIRY=24h` and
`MINIO_API_STALE_UPLOADS_CLEANUP_INTERVAL=1h` explicitly bound stale multipart
retention. These are **instance-wide**, not bucket IAM/lifecycle settings. They
must not be copied onto a shared MinIO instance. The source implementation checks
multipart age before removal; this is separate from normal completed-object
intent reconciliation. Healthy uploads must finish inside 24h. Cleanup scheduling
and service downtime mean 25h is a nominal upper bound while the service is running,
not an uptime-independent deletion SLA.

Production must precreate `researchdesk-documents` and provision a non-admin S3
identity using `infra/minio/researchdesk-policy.json`. The app can list/read/write/delete
and abort multipart only within that bucket; no IAM administration or other bucket
access is granted. Set `STORAGE_ACCESS_KEY` and `STORAGE_SECRET_KEY` for the production
overlay. Admin credentials remain solely with MinIO. The local demo intentionally
uses local development credentials; production values must be unique secrets.

## TLS ingress

Use `compose.yaml`, `compose.production.yaml`, and `compose.tls.yaml` together.
Set `TLS_CERT_DIRECTORY` to a directory containing `fullchain.pem` and `privkey.pem`.
TLS terminates at nginx itself so forwarded scheme and the browser Origin agree.
The default bind remains loopback (`18485`), suitable for a same-host TCP ingress.
Do not put an HTTP-terminating proxy in front without an explicit trusted-proxy
scheme design. Internal databases, Redis, S3 and AI service have no published ports.
The access log records request ID, method, status and duration only. Error logging
is limited to critical infrastructure failures to avoid routine request URI leakage;
application request IDs remain the diagnostic correlation mechanism.

## Isolated multipart regression

After building the image, the test fixture shortens the same native expiration
mechanism to eight seconds. It uses a disposable container with no host ports and
no persistent volume. Never use that override with the normal `minio` service.

```powershell
docker compose -p researchdesk-delivery -f infra/compose.yaml -f infra/compose.multipart-test.yaml --profile multipart-check up -d --no-build minio-multipart-check
docker run --rm --label com.docker.compose.project=researchdesk-delivery --network researchdesk-delivery_default -v "${PWD}/infra/minio/verify-multipart.py:/test.py:ro" --entrypoint python researchdesk-delivery-ai:local /test.py
docker compose -p researchdesk-delivery -f infra/compose.yaml -f infra/compose.multipart-test.yaml --profile multipart-check rm -s -f minio-multipart-check
```

The check writes an old unfinished upload part, waits until S3 ListParts returns
`404 NoSuchUpload`, then verifies a newly initiated part and a completed object remain.
The first test used ListMultipartUploads visibility and failed: the pinned upstream
keeps a separate in-memory listing cache whose startup timer can retain a stale
entry after its disk parts have been removed. Listing visibility is therefore not
the cleanup proof. This limitation is preserved in the acceptance record. It is a real server
check of the same algorithm at an accelerated threshold, not a 24-hour endurance
claim. The production configuration itself fixes the threshold to 24h / 1h.

Local verification on 2026-10-12: the official source image built successfully;
image ID `sha256:75dfef14ff71524e798a8bfb7e938a9574e3df739d2d879d9817d9e92d14d026`.
The real accelerated multipart test passed against this image. See PARALLEL-REPORT.md
for the complete runtime acceptance and remaining integration gates.
