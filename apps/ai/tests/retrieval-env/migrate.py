"""Apply this branch's retrieval test migrations to its dedicated local DB only."""
import hashlib
import os
from pathlib import Path
from urllib.parse import urlsplit
import psycopg

ROOT = Path(__file__).resolve().parents[4]
MIGRATIONS = (
    'V001__identity_projects.sql', 'V002__documents_tasks_outbox.sql',
    'V003__recoverable_ingestion.sql', 'V004__invalidate_removed_versions.sql',
    'V006__scoped_retrieval.sql',
)


def main():
    url = os.environ['RETRIEVAL_TEST_ADMIN_URL']
    parsed = urlsplit(url)
    if parsed.hostname != '127.0.0.1' or parsed.port != 18082 or parsed.path != '/researchdesk':
        raise ValueError('Only the dedicated loopback retrieval test database is supported')
    with psycopg.connect(url, autocommit=True) as connection:
        # The runner verifies the Docker Compose project/service before this call.
        # No table drops or resets: a changed applied migration fails visibly.
        connection.execute('CREATE TABLE IF NOT EXISTS public.retrieval_test_migrations(name text PRIMARY KEY, sha256 text NOT NULL)')
        for name in MIGRATIONS:
            content = (ROOT / 'apps/api/src/main/resources/db/migration' / name).read_bytes()
            checksum = hashlib.sha256(content.replace(b'\r\n', b'\n')).hexdigest()
            existing = connection.execute('SELECT sha256 FROM public.retrieval_test_migrations WHERE name=%s', (name,)).fetchone()
            if existing:
                if existing[0] != checksum:
                    raise ValueError('Previously applied test migration changed: ' + name)
                continue
            with connection.transaction():
                connection.execute(content.decode('utf-8-sig'))
                connection.execute('INSERT INTO public.retrieval_test_migrations(name,sha256) VALUES (%s,%s)', (name,checksum))
        print('PASS isolated retrieval test migrations verified')


if __name__ == '__main__':
    main()
