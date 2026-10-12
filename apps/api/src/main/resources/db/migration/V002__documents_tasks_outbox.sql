CREATE TABLE business.documents (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES business.projects(id) ON DELETE CASCADE,
 title varchar(255) NOT NULL, content_sha256 char(64) NOT NULL,
 state varchar(12) NOT NULL CHECK(state IN ('PROCESSING','READY','FAILED','DELETING','DELETED')),
 error_code varchar(80), error_message text, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(project_id,content_sha256), UNIQUE(id,project_id)
);
CREATE INDEX documents_project_created_idx ON business.documents(project_id,created_at DESC,id);
CREATE TABLE business.document_versions (
 id uuid PRIMARY KEY, document_id uuid NOT NULL, project_id uuid NOT NULL,
 object_key varchar(300) NOT NULL UNIQUE, media_type varchar(80) NOT NULL,
 size_bytes bigint NOT NULL CHECK(size_bytes>0 AND size_bytes<=20971520),
 page_count integer CHECK(page_count>0 AND page_count<=200), parser_version varchar(80) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(document_id,project_id) REFERENCES business.documents(id,project_id) ON DELETE CASCADE,
 UNIQUE(id,project_id), UNIQUE(document_id)
);
CREATE TABLE business.tasks (
 id uuid PRIMARY KEY, project_id uuid NOT NULL, document_version_id uuid NOT NULL,
 attempt_id uuid NOT NULL, fence bigint NOT NULL DEFAULT 1 CHECK(fence>0),
 kind varchar(20) NOT NULL DEFAULT 'PARSE_DOCUMENT' CHECK(kind='PARSE_DOCUMENT'),
 state varchar(12) NOT NULL CHECK(state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
 error_code varchar(80), error_message text, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(document_version_id,project_id) REFERENCES business.document_versions(id,project_id) ON DELETE CASCADE,
 UNIQUE(document_version_id)
);
CREATE INDEX tasks_project_idx ON business.tasks(project_id,created_at DESC,id);
CREATE TABLE business.outbox (
 id uuid PRIMARY KEY, task_id uuid NOT NULL REFERENCES business.tasks(id) ON DELETE CASCADE,
 event_type varchar(30) NOT NULL CHECK(event_type='DOCUMENT_PARSE_REQUESTED'),
 payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz,
 UNIQUE(task_id)
);
CREATE INDEX outbox_pending_idx ON business.outbox(created_at,id) WHERE published_at IS NULL;
-- Persist intent before external writes; successful commit removes it. Orphans survive failed transactions/crashes.
CREATE TABLE business.storage_cleanup (
 object_key varchar(300) PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT now(),
 not_before timestamptz NOT NULL DEFAULT now()+interval '1 hour'
);
CREATE INDEX storage_cleanup_due_idx ON business.storage_cleanup(not_before);
GRANT SELECT,INSERT,UPDATE,DELETE ON business.documents,business.document_versions,business.tasks,business.outbox,business.storage_cleanup TO researchdesk_business;
