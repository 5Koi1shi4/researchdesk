-- Narrow read projection: no business table grants to the AI account.
-- Owner-executed view intentionally reads business state in the query snapshot.
CREATE VIEW retrieval.ready_versions WITH (security_barrier=true) AS
SELECT v.id AS document_version_id, v.project_id
FROM business.document_versions v
JOIN business.documents d ON d.id=v.document_id AND d.project_id=v.project_id
WHERE d.state='READY';
REVOKE ALL ON retrieval.ready_versions FROM PUBLIC, researchdesk_retrieval;
GRANT SELECT ON retrieval.ready_versions TO researchdesk_retrieval;
CREATE INDEX index_versions_project_live_idx ON retrieval.index_versions(project_id,document_version_id)
WHERE active AND published;
-- Exact distance over the materialized authorized scope is deliberate. An ANN
-- global top-k followed by ACL filtering can starve authorized results.
