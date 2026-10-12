# Retrieval integration contract

Task 6/11 module, based on 474b9e5. Apply V006 after existing ingestion migrations.

```python
from retrieval.search import retrieve, RetrievalService
# project and version IDs MUST come from Java's authenticated, ownership-checked
# READY scope, never from a model or user-supplied raw internal request.
evidence = retrieve(project_id, allowed_version_ids, query, limit=8)
# Optional experiments only; baseline stays the default pending real evaluation.
experiment = RetrievalService(mode='hybrid', rerank=True)
```

`Evidence` is an immutable dataclass with `chunk_id`, `document_version_id`,
`text`, `page`, `paragraph`, `score`. No parallel Citation contract was added.
Generation must validate stable chunk IDs against returned evidence and recheck
source availability before final save. A deletion after the retrieval snapshot
cannot erase evidence already read by an in-flight run.

Both ranking paths materialize the authorized database scope before scoring:
project AND allowed versions AND live READY projection AND active/published
index AND current fence AND matching embedding/index metadata. The projection
reads Java-owned business tables without granting Python access to those tables.
Python has SELECT only on this projection. Empty scope does no database/model IO.
A repeatable-read, read-only transaction keeps both hybrid paths on one snapshot.
Queries have a five-second statement timeout and one-second lock timeout.

Vector baseline uses exact cosine distance over the scoped materialized rows,
maximum 20 candidates. Hybrid uses vector top10 plus lexical top10, deduplicates
and applies RRF with k=60: at most 20 unique candidates. Both return at most 8.
Stable chunk ID breaks ties. Optional lexical overlap reranking is deterministic,
not a trained semantic reranker. English tokens and Chinese bigrams are bounded
at 32 unique terms. Scores remain vector similarity or RRF scores, even when
lexical reranking changes their order; callers must not assume descending scores.

## Index compatibility and limits

Current ingestion chunks each parsed section into at most 1,000 characters,
without overlap, preserving page/paragraph. Parser/index version: `sections-v1`.
Embedding: `deterministic-demo-sha256-v1`, 32 dimensions, normalized SHA-256 bytes.
These are demonstration vectors, with no semantic-quality claim. The schema's
`vector(32)` and model check constraints deliberately reject a replacement
embedding space. Replacing the model requires a separately versioned migration,
reindexing every source, matching query embedding adapter and a new evaluation
configuration. Do not silently mix real and demo vectors.

No ANN index is added: the current corpus limit and authorization selectivity
favor auditable exact search. The project/live index and existing version/chunk
keys support scope joins. Large-corpus performance and ANN tuning remain future
work requiring measured plans and recall. Lexical scan is bounded by the same
scope and DB timeout, not by an unverified throughput claim.

## Packaging dependency

The base AI Dockerfile copies packages individually and does not yet copy
`retrieval`. Delivery/integration owns packaging and must add
`COPY apps/ai/retrieval retrieval` before its non-root USER instruction, together
with the other feature packages. This branch intentionally does not edit the
shared Dockerfile. No new Python runtime dependencies or API routes are required.

## Verification

The checked-in standalone integration entry point is:

```powershell
./apps/ai/tests/retrieval-env/run.ps1 -Python <interpreter-with-project-dev-dependencies>
```

It starts only `researchdesk-retrieval/test-db`, uses loopback port18082, and
keeps its own `researchdesk-retrieval_verification-data` volume. Ensure this port
is free; the older ad-hoc retrieval postgres service must remain stopped. The
runner checks container project/service labels before migration, applies only
V001/V002/V003/V004/V006 with a checksum journal, and fails if an already applied
migration changes. It runs the five PostgreSQL tests and stops only test-db on
success or failure. It never drops a database or volume. Repeated runs retain
the journal and remove only fixture-owned records via test cleanup.

Unit: `python -m pytest apps/ai/tests/test_retrieval.py apps/ai/tests/test_hybrid.py -q`
with `PYTHONPATH=<clone>/apps/ai`.

The integration tests in `test_retrieval_postgres.py` require an explicitly
configured `RETRIEVAL_TEST_ADMIN_URL` for an isolated migrated PostgreSQL on
loopback port 18082. This opt-in avoids inherited worker tests that hardcode the
main Compose project. Use Compose project `researchdesk-retrieval` only. Provision
the existing roles, apply V001/V002/V003/V004/V006, set the variable, then run:
`python -m pytest apps/ai/tests/test_retrieval_postgres.py -q`.
Tests use the restricted retrieval login for production queries, and an admin
fixture connection only to seed and remove their own UUID-tagged records.

Actual local validation: 14 tests passed including five PostgreSQL tests;
no main-project containers, volumes or records were modified. The earlier ad-hoc database
used `researchdesk-retrieval_postgres-data`; it remains stopped and retained.
The checked-in runner uses its separate verification-data volume and passed both
fresh and repeated migration/test runs.
