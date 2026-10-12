# Interview talking points

ResearchDesk separates browser interaction, Java authorization/transactions and Python parsing/retrieval. PostgreSQL is the task source of truth; Redis dispatch is recoverable. Attempt IDs, leases and monotonic fences prevent old workers publishing success. Object writes require durable cleanup intent because object storage cannot participate in a PostgreSQL transaction.

Explain the tradeoffs with evidence: an Outbox handles enqueue failure; periodic reconciliation recovers lost streams; isolated parser processes bound resource use; cold backups trade downtime for a consistent database/object snapshot. Authorization remains deterministic server logic and is not delegated to the language model. Unknown provider usage remains unknown, not zero cost.

Bring the actual commands and results from task12-acceptance.md/PARALLEL-REPORT.md. Demonstrate an owner isolation test and one real failure recovery. Distinguish unit/component coverage, real infrastructure acceptance and deferred real-model evaluation. The standalone delivery branch cannot claim registration-to-report-export completion until generation, sources and reports are integrated. Describe unresolved items as limitations rather than hypothetical completed features.

Suggested discussion: why a queue is not task truth; how a crashed worker loses publication rights; how deleted sources invalidate future access without rewriting old citation snapshots; why report versions must be immutable; how provider timeouts and unknown token usage should be represented. For features not integrated here, refer to the owning module's implementation and evidence before claiming support.
