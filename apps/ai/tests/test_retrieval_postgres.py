"""Run only against an explicitly opted-in isolated PostgreSQL instance."""
import os
import uuid
from urllib.parse import urlsplit
import pytest
import psycopg

pytestmark = pytest.mark.integration

@pytest.fixture
def db(monkeypatch):
    dsn = os.getenv('RETRIEVAL_TEST_ADMIN_URL')
    if not dsn:
        pytest.skip('Set RETRIEVAL_TEST_ADMIN_URL to an isolated migrated PostgreSQL')
    target=urlsplit(dsn)
    assert target.hostname in ('127.0.0.1','localhost') and target.port==18082, 'retrieval tests require isolated loopback port 18082'
    monkeypatch.setenv('DATABASE_URL', dsn.replace('researchdesk:researchdesk-dev-only', 'researchdesk_retrieval:researchdesk-retrieval-dev-only'))
    with psycopg.connect(dsn, autocommit=True) as conn:
        user, project, other = [str(uuid.uuid4()) for _ in range(3)]
        conn.execute("INSERT INTO business.users(id,email,password_hash) VALUES (%s,%s,'unused')", (user,user+'@test.invalid'))
        for p in (project,other):
            conn.execute("INSERT INTO business.projects(id,owner_id,name) VALUES (%s,%s,'retrieval test')", (p,user))
        versions=[]
        def add(text, *, p=project, state='READY', active=True, published=True, model='deterministic-demo-sha256-v1', fence=1, count=1):
            from ingestion.indexer import embed
            doc,version,chunk,attempt=[str(uuid.uuid4()) for _ in range(4)]
            conn.execute('INSERT INTO business.documents(id,project_id,title,content_sha256,state) VALUES (%s,%s,%s,%s,%s)',(doc,p,text[:100],uuid.uuid4().hex*2,state))
            conn.execute("INSERT INTO business.document_versions(id,document_id,project_id,object_key,media_type,size_bytes,parser_version) VALUES (%s,%s,%s,%s,'text/markdown',1,'sections-v1')",(version,doc,p,version))
            conn.execute("INSERT INTO retrieval.index_versions(document_version_id,project_id,attempt_id,fence,lease_until,active,published,chunk_count,embedding_model) VALUES (%s,%s,%s,2,now(),%s,%s,%s,%s)",(version,p,attempt,active,published,count,model))
            conn.execute("INSERT INTO retrieval.chunks(document_version_id,fence,ordinal,chunk_id,text,embedding,embedding_model) VALUES (%s,%s,0,%s,%s,%s::vector,'deterministic-demo-sha256-v1')",(version,fence,chunk,text,str(embed(text))))
            versions.append(version)
            return version,chunk,doc
        try:
            yield conn,project,other,add
        finally:
            conn.execute('DELETE FROM retrieval.index_versions WHERE project_id = ANY(%s::uuid[])',([project,other],))
            conn.execute('DELETE FROM business.users WHERE id=%s',(user,))


def test_sql_scope_filters_before_ranking_and_revokes_immediately(db):
    from retrieval.search import retrieve
    conn,p,other,add=db
    good,chunk,doc=add('authorized less similar evidence',fence=2)
    foreign,_,_=add('needle',p=other,fence=2)
    processing,_,_=add('needle',state='PROCESSING',fence=2)
    stale,_,_=add('needle',fence=1)
    inactive,_,_=add('needle',active=False,fence=2)
    unpublished,_,_=add('needle',published=False,fence=2)
    incompatible,_,_=add('needle',model='other-space',fence=2)
    empty,_,_=add('needle',count=0,fence=2)
    excluded,_,_=add('needle',fence=2)
    allowed=[good,foreign,processing,stale,inactive,unpublished,incompatible,empty]
    result=retrieve(p,allowed,'needle')
    assert [e.chunk_id for e in result] == [chunk]
    assert result[0].document_version_id == good
    assert retrieve(other,[good],'needle') == []
    conn.execute("UPDATE business.documents SET state='DELETING' WHERE id=%s",(doc,))
    assert retrieve(p,allowed,'needle') == []


def test_retrieval_role_cannot_read_business_tables(db):
    with psycopg.connect(os.environ['DATABASE_URL']) as conn:
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            conn.execute('SELECT * FROM business.documents')


def test_final_results_capped_and_deterministic(db):
    from retrieval.search import retrieve
    _,p,_,add=db
    versions=[add('same',fence=2)[0] for _ in range(25)]
    first=retrieve(p,versions,'same')
    assert len(first)==8
    assert first==retrieve(p,versions,'same')
    assert len(retrieve(p,versions,'same',3))==3

def test_hybrid_uses_same_authorization_scope(db):
    from retrieval.search import RetrievalService
    _,p,other,add=db
    good,chunk,_=add('退款规则 refund policy',fence=2)
    foreign,_,_=add('退款规则 refund policy',p=other,fence=2)
    processing,_,_=add('退款规则 refund policy',state='PROCESSING',fence=2)
    result=RetrievalService(mode='hybrid',rerank=True).retrieve(p,[good,foreign,processing],'退款 refund')
    assert [e.chunk_id for e in result]==[chunk]

def test_authorization_projection_is_select_only(db):
    with psycopg.connect(os.environ['DATABASE_URL']) as conn:
        assert conn.execute("SELECT has_table_privilege(current_user,'retrieval.ready_versions','SELECT')").fetchone()[0]
        for privilege in ('INSERT','UPDATE','DELETE'):
            assert not conn.execute("SELECT has_table_privilege(current_user,'retrieval.ready_versions',%s)",(privilege,)).fetchone()[0]
