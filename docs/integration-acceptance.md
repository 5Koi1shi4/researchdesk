# Integrated acceptance ledger

Target: `researchdesk-test-integration`, loopback HTTP `18087`, PostgreSQL test-only loopback `15487`. The shared `researchdesk` runtime on `18080` and existing module volumes are outside this harness. No volume is removed; cold restore creates a fresh test project on `18088` and preserves its stopped volumes.

Build, verify and smoke now default to the integration project on 18087 (Foundation also runs there). The full command is `scripts/verify.ps1 -Scope Full -Project researchdesk-test-integration -Port 18087`. It requires the final rebuilt runtime and JDK 25 (`C:/Program Files/Microsoft/jdk-25.0.4.7-hotspot`). Real API configuration remains deferred. Generation is explicitly demo in this acceptance command; deterministic 32-dimensional retrieval embeddings verify authorization/lifecycle wiring and are not semantic-model or holdout quality evidence.

## Configuration checks completed

- Runtime safety tests: passed, including forbidden shared ports/projects, missing full journey failure, integration environment and explicit database override selection.
- Compose integration rendering: exit 0; database binds only `127.0.0.1:15487`.
- Recovery isolation plus evaluation scoring unit checks: 33 passed, exit 0. No holdout or real provider invoked.
- Retrieval PostgreSQL fixture collection: five cases collected; actual execution pending rebuilt runtime.
- PowerShell parsing and Python fixture compilation: passed.
- Locked Linux wheels staged for merged renderer, including ReportLab/Pillow. Existing fixed MinIO image inspected; no module or shared container restarted.

## Pending final execution

Final Java/API, Python, web, contract and browser suites, controlled late-completion races, timeout UI, worker SIGKILL/Redis recovery, and report/export cold restoration remain pending until the functional integration commits and final build. No full acceptance claim is made by this intermediate ledger.

The backup drill now checks login, original source hash, published index, two immutable report versions, exact historical Markdown/PDF SHA-256, and a new authenticated write in the restored runtime. It only reports PASS after all assertions. The race phase always restores the owned API/AI/proxy even after failure.

## First packaging attempt

`build.ps1 -Project researchdesk-test-integration -Port 18087` stopped at the Python gate (exit 1): 122 passed, 3 failed, 11 integration cases deselected. Two PDF failures were the host venv missing the merged locked ReportLab dependency; `uv sync --project apps/ai --locked` installed the three missing packages and the five PDF/export tests then passed. The other failure was the new internal terminal-event usage field being rejected by the strict generated RunEvent contract; it was sent to the usage owner for correction. No test was skipped or safety budget raised. Containers had not yet been started.
