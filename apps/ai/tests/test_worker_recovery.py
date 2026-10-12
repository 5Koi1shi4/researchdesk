import importlib.util
import json
import subprocess
import uuid
import time
import urllib.request
import urllib.error
import pytest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def compose(*arguments):
    result = subprocess.run(['docker','compose','-f',str(ROOT/'infra/compose.yaml'),*arguments],capture_output=True,text=True,timeout=30)
    assert result.returncode == 0, result.stdout + result.stderr
    return result.stdout.strip()


def fixture_sql(query):
    return compose('exec','-T','postgres','psql','-U','researchdesk','-d','researchdesk','-v','ON_ERROR_STOP=1','-At','-c',query)


def until(read, expected, seconds=30):
    deadline=time.monotonic()+seconds
    value=read()
    while value!=expected and time.monotonic()<deadline:
        time.sleep(.25);value=read()
    assert value == expected


@pytest.mark.integration
def test_real_worker_rebuilds_lost_queue_and_recovers_storage_outage():
    email='worker-'+str(uuid.uuid4())+'@researchdesk.test'
    project=None; stopped=False; storage_stopped=False
    base='http://localhost:18080'
    def request(path, data=None, cookie=None, csrf=None, content_type='application/json'):
        headers={'Content-Type':content_type}
        if cookie: headers['Cookie']=cookie
        if csrf: headers['X-CSRF-Token']=csrf
        with urllib.request.urlopen(urllib.request.Request(base+path,data=data,headers=headers),timeout=15) as response:
            return json.loads(response.read()), response.headers
    try:
        session,headers=request('/api/auth/register',json.dumps({'email':email,'password':'WorkerFixturePassword9!'}).encode())
        cookie=headers['Set-Cookie'].split(';')[0];csrf=session['csrfToken']
        project=request('/api/projects',b'{"name":"Worker recovery fixture"}',cookie,csrf)[0]['id']
        compose('stop','ai');stopped=True
        boundary='fixture-'+str(uuid.uuid4())
        body=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="worker.md"\r\nContent-Type: text/markdown\r\n\r\n# Worker evidence\n\nRecovery retains provenance.\r\n--'+boundary+'--\r\n').encode()
        upload=request('/api/projects/'+project+'/documents',body,cookie,csrf,'multipart/form-data; boundary='+boundary)[0]
        task=upload['taskId'];version=upload['versionId']
        until(lambda:fixture_sql(f"SELECT published_at IS NOT NULL FROM business.outbox WHERE task_id='{task}'"),'t')
        compose('exec','-T','redis','redis-cli','DEL','researchdesk:ingestion')
        # Lose both the stream and consumer group, then let the durable Outbox rebuild delivery.
        compose('stop','minio');storage_stopped=True
        compose('start','ai');stopped=False
        until(lambda:fixture_sql(f"SELECT retry_count>0 FROM business.tasks WHERE id='{task}'"),'t')
        assert fixture_sql(f"SELECT state FROM business.documents WHERE id='{upload['documentId']}'") == 'PROCESSING'
        compose('start','minio');storage_stopped=False
        until(lambda:request('/api/projects/'+project+'/documents',cookie=cookie)[0][0]['state'],'READY',40)
        assert fixture_sql(f"SELECT state FROM business.tasks WHERE id='{task}'") == 'SUCCEEDED'
        assert fixture_sql(f"SELECT published||':'||chunk_count||':'||embedding_model FROM retrieval.index_versions WHERE document_version_id='{version}'") == 'true:2:deterministic-demo-sha256-v1'
        # Two actual Redis deliveries after success must not append or replace the index.
        payload=fixture_sql(f"SELECT payload::text FROM business.outbox WHERE task_id='{task}'")
        for _ in range(2): compose('exec','-T','redis','redis-cli','XADD','researchdesk:ingestion','*','payload',payload)
        until(lambda:compose('exec','-T','redis','redis-cli','XLEN','researchdesk:ingestion'),'0')
        assert fixture_sql(f"SELECT count(*) FROM retrieval.chunks WHERE document_version_id='{version}'") == '2'
        key=fixture_sql(f"SELECT object_key FROM business.document_versions WHERE id='{version}'")
        fixture_sql(f"INSERT INTO business.storage_cleanup(object_key,not_before) VALUES ('{key}',now())")
        until(lambda:fixture_sql(f"SELECT count(*) FROM business.storage_cleanup WHERE object_key='{key}'"),'0')
        assert 'Recovery retains provenance.' in compose('exec','-T','minio','sh','-c','mc alias set local http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc cat local/researchdesk-documents/'+key)
        # Future document deletion immediately invalidates callbacks and publication.
        fixture_sql(f"UPDATE business.documents SET state='DELETING' WHERE id='{upload['documentId']}'")
        assert fixture_sql(f"SELECT active||':'||published FROM retrieval.index_versions WHERE document_version_id='{version}'") == 'false:false'
        assert urllib.request.urlopen(base+'/internal/tasks/'+task+'/claim').headers.get('Content-Type','').startswith('text/html')
    finally:
        if storage_stopped: compose('start','minio')
        if stopped: compose('start','ai')
        if project:
            compose('exec','-T','minio','sh','-c','mc alias set local http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc rm --recursive --force local/researchdesk-documents/projects/'+project+'/')
            fixture_sql(f"DELETE FROM retrieval.index_versions WHERE project_id='{project}'; DELETE FROM business.storage_cleanup WHERE object_key LIKE 'projects/{project}/%'")
        fixture_sql(f"DELETE FROM business.users WHERE email='{email}'; DELETE FROM business.auth_throttles WHERE key='email:{email}'")


@pytest.mark.integration
def test_cleanup_keeps_transient_intent_and_never_deletes_a_referenced_object():
    key='cleanup-fixture/'+str(uuid.uuid4())
    fixture_sql(f"INSERT INTO business.storage_cleanup(object_key,not_before) VALUES ('{key}',now())")
    try:
        compose('stop','minio')
        # A failed delete retains the intent and moves its next attempt into the future.
        until(lambda:fixture_sql(f"SELECT not_before>now() FROM business.storage_cleanup WHERE object_key='{key}'"),'t',25)
        assert fixture_sql(f"SELECT count(*) FROM business.storage_cleanup WHERE object_key='{key}'") == '1'
    finally:
        compose('start','minio')
    until(lambda:fixture_sql(f"SELECT count(*) FROM business.storage_cleanup WHERE object_key='{key}'"),'0',30)


@pytest.mark.integration
def test_hard_version_delete_invalidates_an_in_flight_publication_guard():
    user,project,document,version,attempt=[str(uuid.uuid4()) for _ in range(5)]
    fixture_sql(f"INSERT INTO business.users(id,email,password_hash) VALUES ('{user}','delete-{user}@researchdesk.test','unused'); INSERT INTO business.projects(id,owner_id,name) VALUES ('{project}','{user}','Delete fixture'); INSERT INTO business.documents(id,project_id,title,content_sha256,state) VALUES ('{document}','{project}','Delete','{'b'*64}','PROCESSING'); INSERT INTO business.document_versions(id,document_id,project_id,object_key,media_type,size_bytes,parser_version) VALUES ('{version}','{document}','{project}','delete-fixture/{version}','text/markdown',1,'test'); INSERT INTO retrieval.index_versions(document_version_id,project_id,attempt_id,fence,lease_until) VALUES ('{version}','{project}','{attempt}',1,now()+interval '1 minute')")
    try:
        fixture_sql(f"DELETE FROM business.document_versions WHERE id='{version}'")
        assert fixture_sql(f"SELECT active||':'||published FROM retrieval.index_versions WHERE document_version_id='{version}'") == 'false:false'
    finally:
        fixture_sql(f"DELETE FROM retrieval.index_versions WHERE document_version_id='{version}'; DELETE FROM business.users WHERE id='{user}'")


@pytest.mark.integration
def test_exact_retry_budget_backoff_and_permanent_failure():
    user,project,document,version,task,attempt=[str(uuid.uuid4()) for _ in range(6)]
    suffix=task.replace('-','')
    fixture_sql(f"INSERT INTO business.users(id,email,password_hash) VALUES ('{user}','budget-{user}@researchdesk.test','unused'); INSERT INTO business.projects(id,owner_id,name) VALUES ('{project}','{user}','Budget fixture'); INSERT INTO business.documents(id,project_id,title,content_sha256,state) VALUES ('{document}','{project}','Budget','{'c'*64}','PROCESSING'); INSERT INTO business.document_versions(id,document_id,project_id,object_key,media_type,size_bytes,parser_version) VALUES ('{version}','{document}','{project}','budget-fixture/{version}','text/markdown',1,'test'); INSERT INTO business.tasks(id,project_id,document_version_id,attempt_id,state) VALUES ('{task}','{project}','{version}','{attempt}','QUEUED'); CREATE FUNCTION business.budget_{suffix}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.task_id='{task}' THEN NEW.available_at=now()+interval '1 day'; END IF; RETURN NEW; END $$; CREATE TRIGGER budget_{suffix} BEFORE INSERT OR UPDATE ON business.outbox FOR EACH ROW EXECUTE FUNCTION business.budget_{suffix}()")
    def call(action,attempt,fence,**extra):
        body=json.dumps({'attemptId':attempt,'fence':fence,**extra})
        source=f"import os,urllib.request;req=urllib.request.Request('http://api:8080/internal/tasks/{task}/{action}',data={body!r}.encode(),headers={{'Content-Type':'application/json','Authorization':'Bearer '+os.environ['INTERNAL_SERVICE_TOKEN']}});r=urllib.request.urlopen(req,timeout=5);print(r.status)"
        return compose('exec','-T','ai','python','-c',source)
    try:
        for number in range(4):
            attempt,fence=fixture_sql(f"SELECT attempt_id||':'||fence FROM business.tasks WHERE id='{task}'").split(':')
            fixture_sql(f"UPDATE business.tasks SET available_at=now() WHERE id='{task}'")
            assert call('claim',attempt,int(fence)) == '200'
            assert call('fail',attempt,int(fence),code='STORAGE_UNAVAILABLE',permanent=False) == '200'
            if number<3:
                delay=float(fixture_sql(f"SELECT extract(epoch FROM available_at-clock_timestamp()) FROM business.tasks WHERE id='{task}'"))
                assert (2**(number+1))-1.5 < delay <= (2**(number+1))+.5
                assert fixture_sql(f"SELECT state||':'||retry_count||':'||fence FROM business.tasks WHERE id='{task}'") == f'QUEUED:{number+1}:{number+2}'
        assert fixture_sql(f"SELECT state||':'||retry_count FROM business.tasks WHERE id='{task}'") == 'FAILED:3'
        assert fixture_sql(f"SELECT state||':'||error_code FROM business.documents WHERE id='{document}'") == 'FAILED:INGESTION_UNAVAILABLE'
        # A separate manual cycle must still stop immediately for permanent errors.
        fixture_sql(f"UPDATE business.tasks SET state='QUEUED',retry_count=0,available_at=now() WHERE id='{task}'; UPDATE business.documents SET state='PROCESSING' WHERE id='{document}'")
        assert call('claim',attempt,int(fence)) == '200'
        assert call('fail',attempt,int(fence),code='DOCUMENT_NO_TEXT',permanent=False) == '200'
        assert fixture_sql(f"SELECT state||':'||retry_count||':'||error_code FROM business.tasks WHERE id='{task}'") == 'FAILED:0:DOCUMENT_NO_TEXT'
    finally:
        fixture_sql(f"DROP TRIGGER budget_{suffix} ON business.outbox; DROP FUNCTION business.budget_{suffix}(); DELETE FROM retrieval.index_versions WHERE document_version_id='{version}'; DELETE FROM business.users WHERE id='{user}'")



def test_worker_modules_are_available_and_embedding_is_labelled():
    assert importlib.util.find_spec('worker') is not None, 'recoverable Redis consumer is missing'
    from ingestion.indexer import embed, EMBEDDING_MODEL
    assert EMBEDDING_MODEL == 'deterministic-demo-sha256-v1'
    assert embed('evidence') == embed('evidence')
    assert embed('evidence') != embed('different')
    assert len(embed('evidence')) == 32


def test_embedding_adapter_rejects_incompatible_spaces_before_writing():
    from ingestion.indexer import index_document, IncompatibleEmbedding
    class OtherSpace:
        model='real-model-not-configured';dimensions=1536
        def embed(self,text): return [0.0]*1536
    import pytest
    with pytest.raises(IncompatibleEmbedding): index_document({},[],OtherSpace())


@pytest.mark.integration
def test_real_postgres_duplicate_partial_crash_and_stale_publication():
    assert importlib.util.find_spec('ingestion') is not None, 'real PostgreSQL indexer is missing'
    version, project, attempt = [str(uuid.uuid4()) for _ in range(3)]
    def sql(query):
        result = subprocess.run(['docker','compose','-f',str(ROOT/'infra/compose.yaml'),'exec','-T','postgres','psql','-U','researchdesk','-d','researchdesk','-v','ON_ERROR_STOP=1','-At','-c',query],capture_output=True,text=True,timeout=15)
        assert result.returncode == 0, result.stdout + result.stderr
        return result.stdout.strip()
    sql(f"INSERT INTO retrieval.index_versions(document_version_id,project_id,attempt_id,fence,active,lease_until) VALUES ('{version}','{project}','{attempt}',1,true,now()+interval '1 minute')")
    suffix=version.replace('-','')
    sql(f"CREATE FUNCTION retrieval.partial_{suffix}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.document_version_id='{version}' AND NEW.ordinal=1 THEN RAISE EXCEPTION 'owned fixture interrupted index'; END IF; RETURN NEW; END $$; CREATE TRIGGER partial_{suffix} BEFORE INSERT ON retrieval.chunks FOR EACH ROW EXECUTE FUNCTION retrieval.partial_{suffix}()")
    source = '''
import os, uuid, psycopg
from ingestion.indexer import index_document, StaleAttempt
from ingestion.parser import ParsedSection
c = psycopg.connect(os.environ['DATABASE_URL'])
v, p, a = FIXTURE
t = dict(documentVersionId=v,projectId=p,attemptId=a,fence=1)
try:
 sections = [ParsedSection('First evidence',None,1),ParsedSection('Second evidence',None,2)]
 # A real committed partial write must remain unpublished.
 c.execute("INSERT INTO retrieval.chunks(document_version_id,fence,ordinal,chunk_id,text,embedding,embedding_model) VALUES (%s,1,0,%s,'partial',%s::vector,'deterministic-demo-sha256-v1')",(v,str(uuid.uuid4()),'['+','.join(['0']*32)+']')); c.commit()
 assert c.execute('SELECT published FROM retrieval.index_versions WHERE document_version_id=%s',(v,)).fetchone()[0] is False
 try: index_document(t,sections); raise AssertionError('partial fault was not triggered')
 except psycopg.errors.RaiseException: pass
 assert c.execute('SELECT published FROM retrieval.index_versions WHERE document_version_id=%s',(v,)).fetchone()[0] is False
 assert c.execute('SELECT count(*) FROM retrieval.chunks WHERE document_version_id=%s',(v,)).fetchone()[0] == 1
 c.commit()
 print('PASS interrupted atomic write rolled back and remained unpublished')
finally:
 c.rollback(); c.close()
print('PASS real duplicate/partial crash/stale publication')
'''
    try:
        source = source.replace('FIXTURE', repr((version, project, attempt)))
        result = subprocess.run(['docker','compose','-f',str(ROOT/'infra/compose.yaml'),'exec','-T','ai','python','-c',source], capture_output=True, text=True, timeout=30)
        assert result.returncode == 0, result.stdout + result.stderr
        sql(f"DROP TRIGGER partial_{suffix} ON retrieval.chunks; DROP FUNCTION retrieval.partial_{suffix}()")
        recovery=f'''
import os,psycopg
from ingestion.indexer import index_document,StaleAttempt
from ingestion.parser import ParsedSection
t=dict(documentVersionId={version!r},projectId={project!r},attemptId={attempt!r},fence=1)
sections=[ParsedSection('First evidence',None,1),ParsedSection('Second evidence',None,2)]
index_document(t,sections);index_document(t,sections)
with psycopg.connect(os.environ['DATABASE_URL']) as c:
 assert c.execute('SELECT count(*) FROM retrieval.chunks WHERE document_version_id=%s',({version!r},)).fetchone()[0]==2
 assert c.execute('SELECT published,chunk_count FROM retrieval.index_versions WHERE document_version_id=%s',({version!r},)).fetchone()==(True,2)
 try: index_document({{**t,'fence':0}},sections);raise AssertionError('stale publication accepted')
 except StaleAttempt: pass
 c.commit()
 try: c.execute('UPDATE retrieval.index_versions SET fence=2 WHERE document_version_id=%s',({version!r},));raise AssertionError('retrieval role overwrote Java fence')
 except psycopg.errors.InsufficientPrivilege: c.rollback()
print('PASS recovery, duplicate, stale publication and role boundary')
'''
        compose('exec','-T','ai','python','-c',recovery)
    finally:
        sql(f"DROP TRIGGER IF EXISTS partial_{suffix} ON retrieval.chunks; DROP FUNCTION IF EXISTS retrieval.partial_{suffix}()")
        sql(f"DELETE FROM retrieval.index_versions WHERE document_version_id='{version}'")
