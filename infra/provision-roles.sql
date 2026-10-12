\set ON_ERROR_STOP on
\getenv business_password BUSINESS_DATABASE_PASSWORD
\getenv retrieval_password RETRIEVAL_DATABASE_PASSWORD
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='researchdesk_business') THEN CREATE ROLE researchdesk_business LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT; END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='researchdesk_retrieval') THEN CREATE ROLE researchdesk_retrieval LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT; END IF;
END $$;
SELECT format('ALTER ROLE researchdesk_business PASSWORD %L', :'business_password') \gexec
SELECT format('ALTER ROLE researchdesk_retrieval PASSWORD %L', :'retrieval_password') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
