CREATE TABLE business.usage_records (
 run_id uuid PRIMARY KEY REFERENCES business.generation_runs(id) ON DELETE CASCADE,
 provider varchar(80), model varchar(200), mode varchar(8) NOT NULL CHECK(mode IN ('DEMO','REAL')),
 input_tokens bigint CHECK(input_tokens>=0), output_tokens bigint CHECK(output_tokens>=0), total_tokens bigint CHECK(total_tokens>=0),
 duration_ms bigint CHECK(duration_ms>=0), retrieval_ms bigint CHECK(retrieval_ms>=0), ttft_ms bigint CHECK(ttft_ms>=0), queue_wait_ms bigint CHECK(queue_wait_ms>=0),
 estimated_cost numeric(38,12) CHECK(estimated_cost>=0), currency varchar(3), price_date date,
 input_price_per_million numeric(24,12), output_price_per_million numeric(24,12),
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(estimated_cost IS NULL OR (mode='REAL' AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL AND total_tokens IS NOT NULL AND currency IS NOT NULL AND price_date IS NOT NULL))
);
GRANT SELECT,INSERT ON business.usage_records TO researchdesk_business;
