import os
import re
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


def compose_command(*arguments):
    project = os.environ.get('COMPOSE_PROJECT_NAME', '')
    if not re.fullmatch(r'researchdesk-(delivery|test-[a-z0-9-]+)', project):
        raise ValueError('Recovery requires an explicit isolated Compose project')
    base = os.environ.get('ACCEPTANCE_URL', 'http://127.0.0.1:18085')
    target = urllib.parse.urlsplit(base)
    if target.port in range(18080,18085):
        raise ValueError('Recovery refuses shared ports 18080-18084')
    if project != 'researchdesk-delivery' and target.port == 18085:
        raise ValueError('Recovery requires an isolated test port')
    if target.port is None or not 1024 <= target.port <= 65535:
        raise ValueError('Recovery requires an isolated explicit port')
    if target.hostname not in ('localhost', '127.0.0.1', '::1') or target.scheme != 'http' or target.path not in ('', '/') or target.query or target.fragment or target.username:
        raise ValueError('Recovery requires an isolated loopback origin')
    if project == 'researchdesk-delivery' and target.port != 18085:
        raise ValueError('Delivery recovery requires isolated port 18085')
    return ['docker', 'compose', '-p', project, '-f', str(ROOT/'infra/compose.yaml'), *arguments]


def compose(*arguments):
    result = subprocess.run(compose_command(*arguments),capture_output=True,text=True,encoding="utf-8",timeout=30)
    assert result.returncode == 0, result.stdout + result.stderr
    return result.stdout.strip()


def assert_live_target():
    compose_command('ps')
    port = urllib.parse.urlsplit(os.environ.get('ACCEPTANCE_URL', 'http://127.0.0.1:18085')).port
    binding = compose('port', 'proxy', '8080')
    if binding != f'127.0.0.1:{port}':
        raise ValueError('HTTP target does not match isolated project proxy binding')


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
    assert_live_target()
    email='worker-'+str(uuid.uuid4())+'@researchdesk.test'
    project=None; stopped=False; storage_stopped=False
    base=os.environ.get('ACCEPTANCE_URL', 'http://127.0.0.1:18085')
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
        compose('up','-d','--no-build','--no-deps','ai');stopped=False
        assert json.loads(compose('ps','--all','--format','json','minio'))['State']=='exited', 'Fault test accidentally restarted storage'
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
        assert 'Recovery retains provenance.' in compose('exec','-T','ai','python','-c',f'from worker.consumer import read_object; print(read_object({key!r}).decode())')
        # Future document deletion immediately invalidates callbacks and publication.
        fixture_sql(f"UPDATE business.documents SET state='DELETING' WHERE id='{upload['documentId']}'")
        assert fixture_sql(f"SELECT active||':'||published FROM retrieval.index_versions WHERE document_version_id='{version}'") == 'false:false'
        assert urllib.request.urlopen(base+'/internal/tasks/'+task+'/claim').headers.get('Content-Type','').startswith('text/html')
    finally:
        if storage_stopped: compose('start','minio')
        if stopped: compose('start','ai')
        if project:
            fixture_sql(f"INSERT INTO business.storage_cleanup(object_key,not_before) SELECT object_key,now() FROM business.document_versions WHERE project_id='{project}' ON CONFLICT(object_key) DO NOTHING; DELETE FROM retrieval.index_versions WHERE project_id='{project}'")
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
        result = subprocess.run(compose_command('exec','-T','postgres','psql','-U','researchdesk','-d','researchdesk','-v','ON_ERROR_STOP=1','-At','-c',query),capture_output=True,text=True,encoding="utf-8",timeout=15)
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
        result = subprocess.run(compose_command('exec','-T','ai','python','-c',source), capture_output=True, text=True,encoding="utf-8", timeout=30)
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

@pytest.mark.integration
def test_sigkill_running_worker_and_redis_outage_preserve_durable_tasks():
    """Kill an actual worker blocked during index write, then recover via the lease."""
    # Validate the project before creating users or invoking any disruptive command.
    assert_live_target()
    email = 'recovery-' + str(uuid.uuid4()) + '@researchdesk.test'
    base = os.environ.get('ACCEPTANCE_URL', 'http://127.0.0.1:18085')
    project = None
    redis_stopped = False
    worker_killed = False
    lock_process = None
    lock_name = 'recovery_' + uuid.uuid4().hex
    def request(path, data=None, cookie=None, csrf=None, content_type='application/json'):
        headers = {'Content-Type': content_type}
        if cookie: headers['Cookie'] = cookie
        if csrf: headers['X-CSRF-Token'] = csrf
        with urllib.request.urlopen(urllib.request.Request(base + path, data=data, headers=headers), timeout=20) as response:
            return json.loads(response.read()), response.headers
    def upload(text):
        boundary = 'recovery-' + uuid.uuid4().hex
        body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="recovery.md"\r\nContent-Type: text/markdown\r\n\r\n# Recovery\n\n' + text + '\r\n--' + boundary + '--\r\n').encode()
        return request('/api/projects/' + project + '/documents', body, cookie, csrf, 'multipart/form-data; boundary=' + boundary)[0]
    def release_lock():
        fixture_sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='" + lock_name + "' AND pid<>pg_backend_pid()")
        if lock_process: lock_process.communicate(timeout=10)
    try:
        session, headers = request('/api/auth/register', json.dumps({'email': email, 'password': 'RecoveryFixturePassword9!'}).encode())
        cookie = headers['Set-Cookie'].split(';')[0]
        csrf = session['csrfToken']
        project = request('/api/projects', b'{"name":"Recovery acceptance"}', cookie, csrf)[0]['id']
        lock_process = subprocess.Popen(compose_command('exec', '-T', '-e', 'PGAPPNAME=' + lock_name, 'postgres', 'psql', '-U', 'researchdesk', '-d', 'researchdesk', '-v', 'ON_ERROR_STOP=1', '-c', "BEGIN; LOCK retrieval.chunks IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(60); ROLLBACK"), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        until(lambda: fixture_sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='" + lock_name + "' AND wait_event='PgSleep'"), '1')
        first = upload('A killed worker must never publish a partial index.')
        task = first['taskId']
        until(lambda: fixture_sql(f"SELECT state FROM business.tasks WHERE id='{task}'"), 'RUNNING')
        assert fixture_sql(f"SELECT state FROM business.documents WHERE id='{first['documentId']}'") == 'PROCESSING'
        compose('kill', '-s', 'SIGKILL', 'ai')
        worker_killed = True
        release_lock()
        compose('start', 'ai')
        worker_killed = False
        until(lambda: fixture_sql(f"SELECT state FROM business.tasks WHERE id='{task}'"), 'SUCCEEDED', 90)
        assert int(fixture_sql(f"SELECT fence FROM business.tasks WHERE id='{task}'")) >= 2
        assert fixture_sql(f"SELECT published||':'||chunk_count FROM retrieval.index_versions WHERE document_version_id='{first['versionId']}'") == 'true:2'
        compose('stop', 'redis')
        redis_stopped = True
        second = upload('Redis interruption must retain the PostgreSQL task and outbox.')
        task = second['taskId']
        assert fixture_sql(f"SELECT t.state||':'||(o.published_at IS NULL) FROM business.tasks t JOIN business.outbox o ON o.task_id=t.id WHERE t.id='{task}'") == 'QUEUED:true'
        compose('start', 'redis')
        redis_stopped = False
        until(lambda: fixture_sql(f"SELECT state FROM business.tasks WHERE id='{task}'"), 'SUCCEEDED', 60)
    finally:
        if lock_process and lock_process.poll() is None: release_lock()
        if worker_killed: compose('start', 'ai')
        if redis_stopped: compose('start', 'redis')
        if project:
            # Use the application's durable cleanup after removing only this fixture.
            fixture_sql(f"INSERT INTO business.storage_cleanup(object_key,not_before) SELECT object_key,now() FROM business.document_versions WHERE project_id='{project}' ON CONFLICT(object_key) DO NOTHING; DELETE FROM retrieval.index_versions WHERE project_id='{project}'")
        fixture_sql(f"DELETE FROM business.users WHERE email='{email}'; DELETE FROM business.auth_throttles WHERE key='email:{email}'")
