CREATE EXTENSION IF NOT EXISTS vector;
ALTER TABLE business.tasks ADD COLUMN retry_count integer NOT NULL DEFAULT 0 CHECK(retry_count BETWEEN 0 AND 3);
ALTER TABLE business.tasks ADD COLUMN lease_until timestamptz;
ALTER TABLE business.tasks ADD COLUMN available_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE business.outbox ADD COLUMN available_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX tasks_recovery_idx ON business.tasks(lease_until,id) WHERE state='RUNNING';
CREATE INDEX tasks_queued_idx ON business.tasks(available_at,id) WHERE state='QUEUED';
CREATE TABLE retrieval.index_versions (
 document_version_id uuid PRIMARY KEY, project_id uuid NOT NULL,
 attempt_id uuid NOT NULL, fence bigint NOT NULL CHECK(fence>0),
 active boolean NOT NULL DEFAULT true, lease_until timestamptz NOT NULL,
 published boolean NOT NULL DEFAULT false, chunk_count integer NOT NULL DEFAULT 0 CHECK(chunk_count>=0),
 embedding_model varchar(100) NOT NULL DEFAULT 'deterministic-demo-sha256-v1',
 embedding_dimensions integer NOT NULL DEFAULT 32 CHECK(embedding_dimensions=32),
 index_version varchar(80) NOT NULL DEFAULT 'sections-v1',
 published_at timestamptz,
 UNIQUE(document_version_id,fence)
);
CREATE TABLE retrieval.chunks (
 document_version_id uuid NOT NULL REFERENCES retrieval.index_versions(document_version_id) ON DELETE CASCADE,
 fence bigint NOT NULL, ordinal integer NOT NULL CHECK(ordinal>=0), chunk_id uuid NOT NULL UNIQUE,
 text text NOT NULL CHECK(length(text)>0 AND length(text)<=1000),
 page integer CHECK(page>0 AND page<=200), paragraph integer CHECK(paragraph>0),
 embedding vector(32) NOT NULL, embedding_model varchar(100) NOT NULL CHECK(embedding_model='deterministic-demo-sha256-v1'),
 PRIMARY KEY(document_version_id,fence,ordinal)
);
-- Preserve schema ownership: Java can only invoke typed guard operations, never retrieval DML.
REVOKE ALL ON retrieval.index_versions FROM researchdesk_retrieval;
GRANT SELECT ON retrieval.index_versions TO researchdesk_retrieval;
GRANT UPDATE(published,chunk_count,published_at) ON retrieval.index_versions TO researchdesk_retrieval;
GRANT SELECT,INSERT,UPDATE,DELETE ON retrieval.chunks TO researchdesk_retrieval;
CREATE FUNCTION business.claim_index(p_version uuid,p_project uuid,p_attempt uuid,p_fence bigint,p_lease timestamptz) RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 INSERT INTO retrieval.index_versions(document_version_id,project_id,attempt_id,fence,active,lease_until)
 VALUES(p_version,p_project,p_attempt,p_fence,true,p_lease)
 ON CONFLICT(document_version_id) DO UPDATE SET attempt_id=excluded.attempt_id,fence=excluded.fence,active=true,lease_until=excluded.lease_until,published=false,chunk_count=0,published_at=NULL;
$$;
CREATE FUNCTION business.renew_index(p_version uuid,p_attempt uuid,p_fence bigint,p_lease timestamptz) RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 UPDATE retrieval.index_versions SET lease_until=p_lease WHERE document_version_id=p_version AND attempt_id=p_attempt AND fence=p_fence AND active;
$$;
CREATE FUNCTION business.invalidate_index(p_version uuid) RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 UPDATE retrieval.index_versions SET active=false,published=false WHERE document_version_id=p_version;
$$;
CREATE FUNCTION business.published_index_count(p_version uuid,p_attempt uuid,p_fence bigint) RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE expected integer; actual integer;
BEGIN
 SELECT chunk_count INTO expected FROM retrieval.index_versions WHERE document_version_id=p_version AND attempt_id=p_attempt AND fence=p_fence AND active AND published AND lease_until>clock_timestamp() FOR UPDATE;
 IF expected IS NULL OR expected<1 THEN RETURN 0; END IF;
 SELECT count(*) INTO actual FROM retrieval.chunks WHERE document_version_id=p_version AND fence=p_fence;
 RETURN CASE WHEN actual=expected THEN expected ELSE 0 END;
END $$;
REVOKE ALL ON FUNCTION business.claim_index(uuid,uuid,uuid,bigint,timestamptz),business.renew_index(uuid,uuid,bigint,timestamptz),business.invalidate_index(uuid),business.published_index_count(uuid,uuid,bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION business.claim_index(uuid,uuid,uuid,bigint,timestamptz),business.renew_index(uuid,uuid,bigint,timestamptz),business.invalidate_index(uuid),business.published_index_count(uuid,uuid,bigint) TO researchdesk_business;
-- Deletion immediately invalidates a worker even before the lifecycle task removes its index.
CREATE FUNCTION business.invalidate_document_index() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.state IN ('DELETING','DELETED') THEN
  UPDATE retrieval.index_versions SET active=false,published=false WHERE document_version_id IN (SELECT id FROM business.document_versions WHERE document_id=NEW.id);
  UPDATE business.tasks SET state='CANCELLED',fence=fence+1,lease_until=NULL WHERE document_version_id IN (SELECT id FROM business.document_versions WHERE document_id=NEW.id) AND state IN ('QUEUED','RUNNING');
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION business.invalidate_document_index() FROM PUBLIC;
CREATE TRIGGER invalidate_document_index AFTER UPDATE OF state ON business.documents FOR EACH ROW EXECUTE FUNCTION business.invalidate_document_index();
