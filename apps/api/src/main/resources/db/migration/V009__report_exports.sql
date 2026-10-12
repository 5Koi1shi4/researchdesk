CREATE TABLE business.report_exports (
 id uuid PRIMARY KEY, report_id uuid NOT NULL, version integer NOT NULL,
 format varchar(8) NOT NULL CHECK(format IN ('MARKDOWN','PDF')),
 state varchar(12) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
 object_key varchar(300) NOT NULL UNIQUE, error_code varchar(80),attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 4),
 citation_snapshot jsonb, fence bigint NOT NULL DEFAULT 0, lease_until timestamptz, available_at timestamptz NOT NULL DEFAULT now(),created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(report_id,version) REFERENCES business.report_versions(report_id,version) ON DELETE CASCADE
);
CREATE INDEX report_exports_pending_idx ON business.report_exports(available_at,id) WHERE state IN ('QUEUED','RUNNING');
CREATE INDEX report_exports_report_idx ON business.report_exports(report_id,created_at DESC,id);
GRANT SELECT,INSERT,UPDATE,DELETE ON business.report_exports TO researchdesk_business;
