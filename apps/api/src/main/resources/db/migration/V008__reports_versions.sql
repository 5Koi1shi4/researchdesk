CREATE TABLE business.reports (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES business.projects(id) ON DELETE CASCADE,
 title varchar(200) NOT NULL, current_version integer NOT NULL DEFAULT 1 CHECK(current_version>0),
 source_run_id uuid UNIQUE, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reports_project_created_idx ON business.reports(project_id,created_at DESC,id);
CREATE TABLE business.report_versions (
 report_id uuid NOT NULL REFERENCES business.reports(id) ON DELETE CASCADE,
 version integer NOT NULL CHECK(version>0), markdown text NOT NULL CHECK(length(markdown)<=200000),
 citations jsonb NOT NULL CHECK(jsonb_typeof(citations)='array'), demo boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(report_id,version)
);
GRANT SELECT,INSERT,UPDATE,DELETE ON business.reports TO researchdesk_business;
GRANT SELECT,INSERT ON business.report_versions TO researchdesk_business;
-- Narrow function exposes only a requested canonical citation, with project/version checks.
CREATE FUNCTION business.report_citation(p_project uuid,p_version uuid,p_chunk uuid)
RETURNS TABLE(chunk_id uuid,document_version_id uuid,title text,page integer,paragraph integer,excerpt text)
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT c.chunk_id,v.id,d.title::text,c.page,c.paragraph,c.text
 FROM business.document_versions v JOIN business.documents d ON d.id=v.document_id AND d.project_id=v.project_id
 JOIN retrieval.index_versions i ON i.document_version_id=v.id AND i.project_id=v.project_id AND i.active AND i.published
 JOIN retrieval.chunks c ON c.document_version_id=i.document_version_id AND c.fence=i.fence
 WHERE v.project_id=p_project AND v.id=p_version AND c.chunk_id=p_chunk AND d.state='READY';
$$;
REVOKE ALL ON FUNCTION business.report_citation(uuid,uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION business.report_citation(uuid,uuid,uuid) TO researchdesk_business;
