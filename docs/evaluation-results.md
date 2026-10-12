# Evaluation implementation and diagnostic results

Recorded 2026-10-12. **No real-provider or holdout run was performed. AI quality acceptance remains NOT_EVALUATED.** The user deferred provider configuration until project completion.

## What ran

60 authored questions and CC0 fictional Markdown sources, fixed dev40/holdout20. Five categories match the approved design (20/15/10/5/10). All sources, including unauthorized/deleted decoys, are checked against fixture text. Only the 40 development questions were executed. 31 have nonempty positive-source gold sets and contribute to recall.

Environment: Windows PowerShell, repository-local Python module paths, existing shared Python venv used read-only. Evaluation tests use a local HTTP server and synthetic provider replies; they do not call an external provider. Commands from this clone root:

```powershell
$py = 'E:\test\work\researchdesk\apps\ai\.venv\Scripts\python.exe'
& $py -m pytest apps/ai/tests/test_eval_dataset.py evals/test_score.py -q
& $py -m evals.run --split dev --mode stub --config evals/configs/vector.json --output evals/results/dev-vector.json
& $py -m evals.run --split dev --mode stub --config evals/configs/hybrid.json --output evals/results/dev-hybrid.json
& $py -m evals.run --split dev --mode stub --config evals/configs/hybrid-rerank.json --output evals/results/dev-hybrid-rerank.json
```

Initial red: 8 failures due to missing dataset/scoring/runner. Additional human-review import, explicit release, frozen runtime dependencies, complete safe-configuration binding and citation-occurrence coverage tests were observed failing before fixes. Final focused suite: **17 passed**. Tests cover hand-calculated recall/support/coverage/refusal, unknown usage, local HTTP request/response/error redaction, no fallback, scope filtering, exact run-bound annotation coverage, duplicate/invalid/missing citations, frozen dependency tampering, default holdout lock and a one-shot release gate using synthetic hashes. No holdout questions are executed by the gate test.

## Development fixture comparison

The checked-in three JSON result artifacts contain exact hashes and individual answers/retrieved IDs. These are extract-only demo outputs, not AI answers. Current frozen artifacts use LF UTF-8 and bind shared embedding/fusion/reranker source code as well as dataset/protocol/scoring/runner.

| Configuration | Recall@5 (31 eligible) | Mean latency ms | P95 ms | Actual tokens |
| --- | ---: | ---: | ---: | --- |
| vector baseline | 0.290323 | 0.541 | 1.098 | unknown |
| hybrid RRF | 0.854839 | 1.247 | 2.223 | unknown |
| hybrid + lexical rerank | 0.919355 | 1.240 | 2.454 | unknown |

Latency is one local fixture run and includes Python retrieval/extract formatting, not network/model/database latency. Small differences are not statistically meaningful. Human semantic support, claim coverage, refusal and completion remain null because no human/model answer review has been conducted. The low vector baseline is expected for SHA256 pseudo-vectors with no semantic geometry; keyword fusion's gain on this deliberately lexical corpus is not evidence of real embedding/model quality. Cost is unknown, not zero.

Decision: retain **vector as the production default**; expose hybrid and lexical rerank as measured opt-in configurations. The synthetic experiment shows the comparison tooling works, but it does not justify a semantic retrieval production default change. Reassess with real embeddings and an independently frozen integrated benchmark.

## Remaining acceptance work

- Offline fixture IDs are labels, not production UUIDs. Java scope authorization, real upload/parse/1000-character chunking, database retrieval and generation/report orchestration are separate integration obligations. The fixture runner cannot establish end-to-end application success.
- Real mode implements OpenAI-compatible nonstreaming chat completions from server environment only and hard-fails missing configuration. It changes generation only; retrieval remains the documented demo embedding unless a new indexed benchmark is implemented and frozen.
- Thresholds in protocol.md are fixed engineering choices before benchmarking, not measured quality claims. freeze.json remains explicitly marked independent-review-pending until final review is recorded; no approved holdout release ships here.
- Following explicit future authorization and independent protocol review, --describe-config produces a safe complete configuration hash without reading questions or contacting a provider. A release JSON must bind that hash plus freeze hash, reviewer and user authorization. Actual holdout requires real mode and exclusively reserves its ledger before reading questions. Failures leave the ledger in place; no transparent repeat run.
- Human annotation importer requires all answer citation occurrences in order and computes existence from observed retrieval; reviewers supply semantic support. Two independent reviews and adjudication are still needed. Report rubric/export acceptance is separate and pending integrated report generation.

PowerShell entrypoint smoke: process-scoped `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/evaluate.ps1 ...` returned 0 for dev/stub; with provider environment absent dev/real returned child exit 2 and no fallback. The host default script policy initially blocked execution; no global execution policy was changed. Python module commands remain usable without PowerShell policy changes.
