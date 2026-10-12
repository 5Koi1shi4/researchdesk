# Honest demo walkthrough

Use a private local account created through registration; there is no seeded shared password or account. Open the isolated proxy at http://127.0.0.1:18085 after following deployment.md.

1. Register, create a project, upload a small UTF-8 Markdown file containing facts you authored. Wait for READY; explain the database task, Outbox, worker lease and publication fence.
2. Try an unsupported or empty file and show the controlled validation message. Open a second account and verify the first account's project is inaccessible.
3. On disposable data run `./scripts/test-ingestion.ps1`. This performs real isolated storage/queue/worker recovery tests; present actual output from this run, not a simulated green badge.
4. After feature integration, run `./scripts/smoke.ps1 -Scope Full`: ask a source-grounded question, navigate a citation, edit/save a report version and download Markdown/PDF. Until the full journey is present, stop here and label these steps pending.
5. Explain model mode explicitly. The current deterministic embedding fixture demonstrates pipeline behavior. Generation demo mode and real adapter arrive through the generation branch. Real OpenAI-compatible evaluation is deferred until the user configures it; do not request credentials in the demo or claim model quality scores.

Use only redistributable sample material. Hide local secrets and private user content when presenting logs. Show request/task/run IDs where implemented, not raw prompts or source text. Actual acceptance counts and limitations belong in task12-acceptance.md and PARALLEL-REPORT.md; never invent latency, throughput, cost, or evaluation numbers.
