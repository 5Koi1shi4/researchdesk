"""Live TLS/cookie/CSRF/log-redaction probe against the isolated delivery ingress."""
import datetime
import json
from pathlib import Path
import ssl
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
BASE = 'https://127.0.0.1:18485'


def main():
    context=ssl.create_default_context(cafile=str(ROOT/'.cache/tls-acceptance/fullchain.pem'))
    binding=subprocess.run(['docker','compose','-p','researchdesk-delivery','-f',str(ROOT/'infra/compose.yaml'),'port','proxy','8443'],capture_output=True,text=True,encoding='utf-8',timeout=30)
    assert binding.returncode==0 and binding.stdout.strip()=='127.0.0.1:18485', 'TLS target does not match delivery proxy'
    deadline=time.monotonic()+45
    while True:
        try:
            with urllib.request.urlopen(BASE+'/api/ready',context=context,timeout=5) as probe:
                if probe.status==200 and json.load(probe)['status']=='UP': break
        except urllib.error.URLError as error:
            if isinstance(error.reason,ssl.SSLCertVerificationError): raise
        if time.monotonic()>=deadline: raise AssertionError('TLS API readiness deadline exceeded')
        time.sleep(.3)
    marker='sensitive-'+uuid.uuid4().hex
    email=marker+'@researchdesk.test'
    started=datetime.datetime.now(datetime.timezone.utc).isoformat()
    def request(path, body=None, cookie=None, csrf=None, origin=BASE):
        headers={'Content-Type':'application/json','Origin':origin,'X-Request-Id':marker,'Authorization':'Bearer '+marker}
        if cookie: headers['Cookie']=cookie
        if csrf: headers['X-CSRF-Token']=csrf
        try: response=urllib.request.urlopen(urllib.request.Request(BASE+path,data=body,headers=headers),context=context,timeout=15)
        except urllib.error.HTTPError as failure: response=failure
        with response:
            data=response.read()
            return response.status,json.loads(data) if data else None,response.headers
    try:
        with urllib.request.urlopen(BASE+'/?private='+marker,context=context,timeout=15) as page: assert page.status==200
        status,session,headers=request('/api/auth/register?private='+marker,json.dumps({'email':email,'password':'TlsFixturePassword9!'}).encode())
        assert status==201, status
        full_cookie=headers['Set-Cookie']
        assert all(value in full_cookie for value in ('Secure','HttpOnly','SameSite=Lax'))
        assert headers['X-Content-Type-Options']=='nosniff'
        assert 'max-age=31536000' in headers['Strict-Transport-Security']
        cookie=full_cookie.split(';')[0]
        status,error,error_headers=request('/api/projects',b'{"name":"forbidden"}',cookie)
        assert status==403
        assert error['requestId']==error_headers['X-Request-Id']
        assert len(error['requestId'])==32 and marker not in error['requestId']
        status,_,_=request('/api/projects',b'{"name":"foreign"}',cookie,session['csrfToken'],origin='https://evil.example')
        assert status==403
        status,_,_=request('/api/projects',b'{"name":"TLS verified"}',cookie,session['csrfToken'])
        assert status==201
        logs=subprocess.run(['docker','compose','-p','researchdesk-delivery','-f',str(ROOT/'infra/compose.yaml'),'logs','--since',started,'api','proxy','ai','web'],capture_output=True,text=True,encoding="utf-8",timeout=30)
        assert logs.returncode==0
        assert marker not in logs.stdout+logs.stderr, 'Sensitive marker leaked to container logs'
        assert 'TlsFixturePassword9!' not in logs.stdout+logs.stderr
        assert error['requestId'] in logs.stdout, 'Error request ID absent from logs'
        result={'status':'PASS','verified':['trusted test TLS certificate','Secure HttpOnly SameSite cookie','CSRF denial','cross-origin denial','same-origin write','edge/API/error requestId correlation','sensitive query/header/body excluded from logs']}
        (ROOT/'.cache/tls-security-result.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
        print(json.dumps(result))
    finally:
        result=subprocess.run(['docker','compose','-p','researchdesk-delivery','-f',str(ROOT/'infra/compose.yaml'),'exec','-T','postgres','psql','-U','researchdesk','-d','researchdesk','-v','ON_ERROR_STOP=1','-c',f"DELETE FROM business.users WHERE email='{email}'; DELETE FROM business.auth_throttles WHERE key='email:{email}'"],capture_output=True,text=True,encoding="utf-8",timeout=30)
        assert result.returncode==0, 'Fixture cleanup failed'


if __name__=='__main__': main()
