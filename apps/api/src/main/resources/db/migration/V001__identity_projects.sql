CREATE SCHEMA business;
CREATE SCHEMA retrieval;
REVOKE ALL ON SCHEMA business, retrieval FROM PUBLIC;
CREATE TABLE business.users (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE business.sessions (
    token_hash char(64) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES business.users(id) ON DELETE CASCADE,
    csrf_token varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX sessions_user_idx ON business.sessions(user_id);
CREATE INDEX sessions_expiry_idx ON business.sessions(expires_at);
CREATE TABLE business.projects (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES business.users(id) ON DELETE CASCADE,
    name varchar(120) NOT NULL CHECK (length(trim(name)) > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX projects_owner_created_idx ON business.projects(owner_id, created_at DESC, id);
CREATE TABLE business.auth_throttles (
    key varchar(300) PRIMARY KEY,
    attempts integer NOT NULL CHECK (attempts >= 0),
    window_start timestamptz NOT NULL
);
-- Login roles are provisioned separately, passwords never committed to migrations.
GRANT USAGE ON SCHEMA business TO researchdesk_business;
GRANT SELECT, INSERT, UPDATE, DELETE ON business.users, business.sessions, business.projects, business.auth_throttles TO researchdesk_business;
GRANT USAGE ON SCHEMA retrieval TO researchdesk_retrieval;
ALTER DEFAULT PRIVILEGES IN SCHEMA retrieval GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO researchdesk_retrieval;
