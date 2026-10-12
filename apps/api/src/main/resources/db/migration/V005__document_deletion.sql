CREATE TABLE business.document_deletions (
 document_id uuid PRIMARY KEY REFERENCES business.documents(id) ON DELETE CASCADE,
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz
);
CREATE INDEX document_deletions_due_idx ON business.document_deletions(next_attempt_at,document_id) WHERE completed_at IS NULL;
GRANT SELECT,INSERT,UPDATE,DELETE ON business.document_deletions TO researchdesk_business;
-- This narrow capability preserves retrieval schema ownership and refuses live indexes.
CREATE FUNCTION business.purge_deleted_index(p_document uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM business.documents WHERE id=p_document AND state IN ('DELETING','DELETED')) THEN RAISE EXCEPTION 'Document is active'; END IF;
 DELETE FROM retrieval.index_versions WHERE document_version_id IN (SELECT id FROM business.document_versions WHERE document_id=p_document) AND NOT active;
END $$;
REVOKE ALL ON FUNCTION business.purge_deleted_index(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION business.purge_deleted_index(uuid) TO researchdesk_business;
-- Keep historical versions immutable while allowing a fresh upload after revocation.
ALTER TABLE business.documents DROP CONSTRAINT documents_project_id_content_sha256_key;
CREATE UNIQUE INDEX documents_active_digest_idx ON business.documents(project_id,content_sha256) WHERE state NOT IN ('DELETING','DELETED');
