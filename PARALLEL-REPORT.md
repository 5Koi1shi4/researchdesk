# Parallel implementation report — retrieval (Tasks 6 + 11)

Date: 2026-10-12 (Asia/Shanghai). Branch: parallel/retrieval.
Owned clone: E:/test/work/researchdesk-parallel/retrieval.
Base: 474b9e5070ce94c63b925ff72d06948f087a3f8a. Imported SHAs: none.

## Scope

Implemented the frozen `retrieve(project_id, allowed_version_ids, query, limit=8)`
Evidence contract, exact scoped vector baseline, optional bounded hybrid RRF and
lexical reranking. V006 introduces a SELECT-only READY projection and scoped
index. No common API DTOs, sibling clones or primary repository were changed.

Evaluation deliverables: authored CC0 fixture corpus, fixed 60-question 40/20
split with required categories, frozen metrics/protocol, offline development
runner, explicit demo/real modes, environment-only OpenAI-compatible adapter,
manual-label scoring and guarded holdout release. Details and measured demo
results are in docs/evaluation-results.md. Demo scores are not AI acceptance.

## Commits

- 2297b1e674f0cecd529d3188c4ef2809fb473e6a — scoped vector/hybrid retrieval,
  V006, unit and PostgreSQL regression tests, integration documentation.
- 254f6bc5527a7476f175cddd99a48d84bd62bfff — frozen dataset, scoring, real
  adapter, release guard, development comparisons and tests.
- Final documentation commit contains this report; see git log / final response.

## Final verification evidence

Test-first failures observed: missing retrieval package (6 failed), real database
scope/ranking (2 failed, 1 passed), hybrid behavior (4 failed, 3 passed), and
inherited projection write privileges (1 failed, 4 passed). Each was followed by
implementation and passing tests. No test-only model output was treated as truth.

14 retrieval/hybrid tests passed including five real PostgreSQL tests. Independent
review reran those 14 and found no remaining issue. AI non-integration baseline
regression: 51 passed, 10 deselected at that checkpoint. The integration fixture
now validates the DSN host and port before connecting; its five tests passed.

Final commands (from this clone, with PYTHONPATH=apps/ai plus repository root):

- `python -m pytest apps/ai/tests evals/test_score.py -m "not integration" -q`:
  **67 passed, 10 deselected in 28.31s**. The ten deselected integration tests
  include the five separately executed retrieval tests and five baseline tests
  that address main-project services.
- `python -m pytest apps/ai/tests/test_retrieval_postgres.py -q` with the isolated
  RETRIEVAL_TEST_ADMIN_URL: **5 passed in 6.63s**.
- Independent evaluation review: **17 passed in 3.05s**, three P2 findings fixed:
  freeze shared runtime algorithms, bind release to complete safe config, and
  require every citation occurrence while computing existence from actual IDs.
- `scripts/evaluate.ps1 -Split dev -Mode stub -Config evals/configs/vector.json
  -Python <shared interpreter>` (PowerShell process policy bypass only): exit 0.
  Missing real environment configuration: exit 2, with no fallback.
- Three frozen dev-only comparisons each executed 40 questions; eligible Recall@5
  sample count 31. Vector=.290323, hybrid=.854839, hybrid+rerank=.919355.
  These are nonsemantic demo diagnostics; human metrics and usage stay null.
- Verified every freeze dependency against the exact staged Git blob bytes,
  plus dataset/split/freeze/config hashes in all three result artifacts. Passed.
- `git diff --cached --check`: passed before the evaluation commit.

Independent code review is complete. The freeze metadata deliberately retains
formal acceptance-review-pending status: no approved future holdout release is
provided, and no claim of human quality acceptance is made.

Commands use a read-only interpreter from the main repo's existing venv, with
PYTHONPATH pointing to this clone, not its Python source. No packages were
installed into that shared environment.

## Pending integration and limitations

- Apply V006 after V001–V004. Java remains responsible for authenticating scope
  and rechecking source availability before saving an in-flight generation.
- Delivery owns packaging: add `COPY apps/ai/retrieval retrieval` to the AI
  Dockerfile. Current baseline Dockerfile explicitly copies individual packages.
- Current embedding is labelled nonsemantic SHA256 demo, dimension 32, index
  sections-v1. Production semantic embeddings require a versioned schema/index
  migration and complete reindex, not a model environment-variable swap.
- Offline fixtures use stable authored IDs and paragraphs; their runner does not
  claim Java/DB ingestion, streaming or report-generation end-to-end acceptance.
- Real provider execution and official holdout scores are deferred by the user.
  No credentials were requested; no actual provider or holdout calls were made.
- Report rubric and human semantic labels are defined, not completed. Quality
  thresholds remain unaccepted until future real runs and required human review.
- Exact scoped search and keyword scan are bounded by budgets/timeouts. No large
  corpus throughput or ANN performance claim is made.

## Isolation

Only this clone was written. PostgreSQL runs under Compose project
researchdesk-retrieval with its own volume and loopback port 18082. Existing main
researchdesk containers and port18080 were never operated. Existing worker tests
that hardcode main Compose were explicitly excluded; only the new scoped
PostgreSQL tests were selected. No remote pushes, deployment or main merge.

The owned PostgreSQL service was stopped after verification. Its isolated volume
was retained for inspection; no destructive volume cleanup was performed.

## Continuation verification — 2026-10-12

User requested continuation. Completed two remaining reproducibility/error-path
improvements within this clone:

- Real generation evaluator validates that provider `usage` is an object or
  null. Four malformed response cases first failed with unhandled AttributeError,
  then passed with the established redacted failure. Missing usage remains null.
- Added a checked-in isolated database test environment under
  apps/ai/tests/retrieval-env. It uses fixed Compose project researchdesk-retrieval,
  service test-db, loopback18082, its own verification-data volume, a transactional
  migration checksum journal, and stops the service without dropping data.
- Independent review found a startup-failure cleanup gap. A fault-injected
  PowerShell test reproduced the omitted stop; cleanup now also runs after
  failed startup. The test confirms only the owned project/service is stopped.

Fresh database run: 5 passed in 2.54s. Repeated setup/migration run: 5 passed in
2.60s. Both stopped test-db and preserved the verification-data volume.
Final AI/evaluation non-integration suite: 72 passed, 10 deselected in 23.48s.
Independent review: evaluation/data 21 passed; runner cleanup 1 passed, no
remaining findings. Root infra, shared Dockerfile and sibling clones were not
changed.

Freeze revision library-v1.1 records the original freeze hash and the response
validation change. Dataset, split, protocol and quality thresholds are unchanged.
All three dev-only demo comparisons were regenerated, and every frozen dependency
plus dataset/split/freeze/config hash was verified against staged Git blob bytes.
No external provider or holdout execution occurred. AI quality remains
NOT_EVALUATED. Latest detailed latency values are in docs/evaluation-results.md.

The incremental implementation/report commit is the current branch HEAD; see
final response or git log for its SHA. Integration dependencies above still apply.
