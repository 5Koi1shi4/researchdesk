"""Live cold-backup drill. Requires delivery running; keeps backup and restored volumes."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
SOURCE = 'researchdesk-delivery'
BASE = 'http://127.0.0.1:18085'
RESTORE_PORT = 18086


def run(*args, timeout=180):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True,encoding="utf-8", timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'{args[0]} failed ({result.returncode}): {result.stderr[-2000:]}')
    return result.stdout.strip()


def compose(project, *args):
    if project != SOURCE and not project.startswith('researchdesk-test-delivery-restore-'):
        raise ValueError('Refusing non-owned project')
    return run('docker', 'compose', '-p', project, '-f', str(ROOT/'infra/compose.yaml'), *args)


def sql(project, query):
    return compose(project, 'exec', '-T', 'postgres', 'psql', '-U', 'researchdesk', '-d', 'researchdesk', '-v', 'ON_ERROR_STOP=1', '-At', '-c', query)


def request(base, path, body=None, cookie=None, csrf=None, media='application/json'):
    headers = {'Content-Type': media}
    if cookie: headers['Cookie'] = cookie
    if csrf: headers['X-CSRF-Token'] = csrf
    with urllib.request.urlopen(urllib.request.Request(base+path, data=body, headers=headers), timeout=15) as response:
        data = response.read()
        return json.loads(data) if data else None, response.headers


def until(action, expected, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if action() == expected: return
        except (OSError, RuntimeError): pass
        time.sleep(.5)
    raise AssertionError('Restore readiness/state deadline exceeded')


def main():
    assert compose(SOURCE, 'port', 'proxy', '8080') == '127.0.0.1:18085', 'Source binding mismatch'
    until(lambda: request(BASE, '/api/ready')[0]['status'], 'UP')
    label = 'drill-' + uuid.uuid4().hex[:12]
    target = 'researchdesk-test-delivery-restore-' + label
    email = label + '@researchdesk.test'
    password = 'BackupFixturePassword9!'
    os.environ['COMPOSE_PROJECT_NAME'] = SOURCE
    os.environ['RESEARCHDESK_PORT'] = '18085'
    project = None
    restored = False
    try:
        session, headers = request(BASE, '/api/auth/register', json.dumps({'email':email,'password':password}).encode())
        cookie = headers['Set-Cookie'].split(';')[0]
        csrf = session['csrfToken']
        project = request(BASE, '/api/projects', b'{"name":"Cold recovery fixture"}', cookie, csrf)[0]['id']
        content = b'# Backup evidence\n\nRestored bytes must match the pre-backup source.\n'
        boundary = label
        body = b'--'+boundary.encode()+b'\r\nContent-Disposition: form-data; name="file"; filename="backup.md"\r\nContent-Type: text/markdown\r\n\r\n'+content+b'\r\n--'+boundary.encode()+b'--\r\n'
        upload = request(BASE, '/api/projects/'+project+'/documents', body, cookie, csrf, 'multipart/form-data; boundary='+boundary)[0]
        until(lambda: sql(SOURCE, f"SELECT state FROM business.tasks WHERE id='{upload['taskId']}'"), 'SUCCEEDED')
        key = sql(SOURCE, f"SELECT object_key FROM business.document_versions WHERE id='{upload['versionId']}'")
        start = time.monotonic()
        run('pwsh', '-NoProfile', '-File', str(ROOT/'scripts/backup.ps1'), '-Action', 'Backup', '-Name', label, timeout=300)
        backup_seconds = time.monotonic()-start
        start = time.monotonic()
        run('pwsh', '-NoProfile', '-File', str(ROOT/'scripts/backup.ps1'), '-Action', 'Restore', '-Name', label, '-Project', target, '-Port', str(RESTORE_PORT), timeout=300)
        os.environ['RESEARCHDESK_PORT'] = str(RESTORE_PORT)
        restored = True
        compose(target, 'up', '-d', '--no-build')
        target_base = f'http://127.0.0.1:{RESTORE_PORT}'
        until(lambda: request(target_base, '/api/ready')[0]['status'], 'UP')
        login, headers = request(target_base, '/api/auth/login', json.dumps({'email':email,'password':password}).encode())
        restored_cookie = headers['Set-Cookie'].split(';')[0]
        docs = request(target_base, '/api/projects/'+project+'/documents', cookie=restored_cookie)[0]
        assert len(docs)==1 and docs[0]['state']=='READY'
        digest = compose(target, 'exec', '-T', 'ai', 'python', '-c', f'from worker.consumer import read_object; import hashlib; print(hashlib.sha256(read_object({key!r})).hexdigest())')
        assert digest == hashlib.sha256(content).hexdigest(), 'Restored source bytes differ'
        assert sql(target, f"SELECT published||':'||chunk_count FROM retrieval.index_versions WHERE document_version_id='{upload['versionId']}'") == 'true:2'
        created = request(target_base, '/api/projects', b'{"name":"Post-restore write"}', restored_cookie, login['csrfToken'])[0]
        assert created['id'] != project
        result = {'status':'PASS','scope':'foundation cold backup/restore','backup':label,'restoreProject':target,'backupSeconds':round(backup_seconds,2),'restoreToVerifiedSeconds':round(time.monotonic()-start,2),'verified':['existing login','READY document','exact original bytes SHA256','published index','new authenticated write'],'pending':['report versions and exports after integration']}
        output=ROOT/'.cache'/f'{label}-result.json'
        output.write_text(json.dumps(result, indent=2), encoding='utf-8')
        print(json.dumps(result))
    finally:
        # Never remove volumes: leave the isolated recovered data for inspection.
        if restored: compose(target, 'stop')
        os.environ['RESEARCHDESK_PORT'] = '18085'
        if project:
            sql(SOURCE, f"INSERT INTO business.storage_cleanup(object_key,not_before) SELECT object_key,now() FROM business.document_versions WHERE project_id='{project}' ON CONFLICT(object_key) DO NOTHING; DELETE FROM retrieval.index_versions WHERE project_id='{project}'")
        sql(SOURCE, f"DELETE FROM business.users WHERE email='{email}'; DELETE FROM business.auth_throttles WHERE key='email:{email}'")


if __name__ == '__main__': main()
