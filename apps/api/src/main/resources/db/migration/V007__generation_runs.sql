CREATE TABLE business.conversations (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES business.projects(id) ON DELETE CASCADE,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(id,project_id)
);
CREATE INDEX conversations_project_idx ON business.conversations(project_id,created_at DESC,id);
CREATE TABLE business.generation_runs (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES business.projects(id) ON DELETE CASCADE,
 owner_id uuid NOT NULL REFERENCES business.users(id) ON DELETE CASCADE,
 conversation_id uuid NOT NULL, kind varchar(10) NOT NULL CHECK(kind IN ('ANSWER','REPORT')),
 state varchar(12) NOT NULL CHECK(state IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED')),
 prompt text NOT NULL, document_version_ids jsonb NOT NULL, history jsonb NOT NULL,
 mode varchar(8) NOT NULL CHECK(mode IN ('DEMO','REAL')), text text NOT NULL DEFAULT '',
 citations jsonb NOT NULL DEFAULT '[]', error jsonb, last_seq bigint NOT NULL DEFAULT 0 CHECK(last_seq>=0),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), deadline timestamptz,
 FOREIGN KEY(conversation_id,project_id) REFERENCES business.conversations(id,project_id) ON DELETE CASCADE
);
CREATE INDEX generation_runs_owner_active_idx ON business.generation_runs(owner_id) WHERE state IN ('QUEUED','RUNNING');
CREATE INDEX generation_runs_queued_idx ON business.generation_runs(created_at,id) WHERE state='QUEUED';
CREATE INDEX generation_runs_deadline_idx ON business.generation_runs(deadline) WHERE state='RUNNING';
CREATE INDEX generation_runs_conversation_idx ON business.generation_runs(conversation_id,created_at,id);
CREATE TABLE business.run_events (
 run_id uuid NOT NULL REFERENCES business.generation_runs(id) ON DELETE CASCADE,
 seq bigint NOT NULL CHECK(seq>0), type varchar(12) NOT NULL CHECK(type IN ('started','delta','citation','completed','failed','cancelled')),
 payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(run_id,seq)
);
CREATE INDEX run_events_expiry_idx ON business.run_events(created_at);
GRANT SELECT,INSERT,UPDATE,DELETE ON business.conversations,business.generation_runs,business.run_events TO researchdesk_business;
