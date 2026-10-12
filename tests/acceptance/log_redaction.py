"""Probe query redaction at both public static ingress and internal AI liveness."""
import argparse
import datetime
from pathlib import Path
import re
import subprocess
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[2]

def main():
 parser=argparse.ArgumentParser()
 parser.add_argument('--project',default='researchdesk-delivery')
 parser.add_argument('--port',type=int,default=18085)
 args=parser.parse_args()
 if not re.fullmatch(r'researchdesk-(delivery|test-[a-z0-9-]+)',args.project) or args.port<1024 or args.port>65535 or args.port in range(18080,18085) or (args.port!=18085 if args.project=='researchdesk-delivery' else args.port==18085):
  raise ValueError('Require an isolated project and port')
 def compose(*command):
  result=subprocess.run(['docker','compose','-p',args.project,'-f',str(ROOT/'infra/compose.yaml'),*command],capture_output=True,text=True,encoding='utf-8',timeout=30)
  assert result.returncode==0, 'Compose log probe failed'
  return result.stdout
 assert compose('port','proxy','8080').strip()==f'127.0.0.1:{args.port}', 'Proxy binding mismatch'
 started=datetime.datetime.now(datetime.timezone.utc).isoformat()
 marker='redaction-'+uuid.uuid4().hex
 with urllib.request.urlopen(f'http://127.0.0.1:{args.port}/?private='+marker,timeout=10) as response: assert response.status==200
 compose('exec','-T','ai','python','-c',f"import urllib.request; assert urllib.request.urlopen('http://127.0.0.1:8000/health?private={marker}',timeout=5).status==200")
 logs=compose('logs','--since',started,'proxy','web','api','ai')
 assert marker not in logs, 'Sensitive fixture query leaked into container logs'
 print('PASS public static and internal AI queries are absent from logs')

if __name__=='__main__':main()
