-- Guard hard deletion/cascade as well as the document lifecycle's DELETING transition.
CREATE FUNCTION business.invalidate_removed_version() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 UPDATE retrieval.index_versions SET active=false,published=false WHERE document_version_id=OLD.id;
 RETURN OLD;
END $$;
REVOKE ALL ON FUNCTION business.invalidate_removed_version() FROM PUBLIC;
CREATE TRIGGER invalidate_removed_version AFTER DELETE ON business.document_versions FOR EACH ROW EXECUTE FUNCTION business.invalidate_removed_version();
