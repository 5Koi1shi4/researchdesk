"""Redis consumer groups with database ownership; duplicate deliveries are safe."""
import datetime
import hashlib
import hmac
import json
import logging
import os
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid
import redis
from generated.contracts import InternalTask
from ingestion.indexer import index_document, StaleAttempt
from ingestion.parser import parse_document, ParseError, MAX_BYTES

LOG=logging.getLogger(__name__)
STREAM='researchdesk:ingestion'
GROUP='ingestion-v1'


def callback(task, action, **extra):
    body={'attemptId':str(task['attemptId']),'fence':int(task['fence']),**extra}
    request=urllib.request.Request(os.environ.get('JAVA_INTERNAL_URL','http://api:8080')+'/internal/tasks/'+str(task['taskId'])+'/'+action,data=json.dumps(body).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+os.environ['INTERNAL_SERVICE_TOKEN']})
    with urllib.request.urlopen(request,timeout=5) as response:
        data=response.read(16384)
        return json.loads(data) if data else None


def read_object(key):
    # Private S3 GET, signed without exposing storage credentials or URLs to the browser.
    endpoint=os.environ.get('STORAGE_ENDPOINT','http://minio:9000').rstrip('/')
    host=urllib.parse.urlsplit(endpoint).netloc
    path='/'+urllib.parse.quote(os.environ.get('STORAGE_BUCKET','researchdesk-documents')+'/'+key,safe='/')
    date=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ');day=date[:8]
    digest=hashlib.sha256(b'').hexdigest();scope=day+'/us-east-1/s3/aws4_request'
    headers='host:'+host+'\nx-amz-content-sha256:'+digest+'\nx-amz-date:'+date+'\n';signed='host;x-amz-content-sha256;x-amz-date'
    canonical='GET\n'+path+'\n\n'+headers+'\n'+signed+'\n'+digest
    key_bytes=('AWS4'+os.environ['STORAGE_SECRET_KEY']).encode()
    for value in (day,'us-east-1','s3','aws4_request'): key_bytes=hmac.new(key_bytes,value.encode(),hashlib.sha256).digest()
    signature=hmac.new(key_bytes,('AWS4-HMAC-SHA256\n'+date+'\n'+scope+'\n'+hashlib.sha256(canonical.encode()).hexdigest()).encode(),hashlib.sha256).hexdigest()
    authorization='AWS4-HMAC-SHA256 Credential='+os.environ['STORAGE_ACCESS_KEY']+'/'+scope+', SignedHeaders='+signed+', Signature='+signature
    request=urllib.request.Request(endpoint+path,headers={'Authorization':authorization,'X-Amz-Date':date,'X-Amz-Content-Sha256':digest})
    with urllib.request.urlopen(request,timeout=10) as response:
        content=response.read(MAX_BYTES+1)
        if len(content)>MAX_BYTES: raise ParseError('UPLOAD_TOO_LARGE')
        return content


def process_message(payload):
    task=InternalTask.model_validate_json(payload).model_dump(mode='json')
    try: claim=callback(task,'claim')
    except urllib.error.HTTPError as error:
        if error.code==409: return
        raise
    # Use Java's canonical payload, not untrusted queue metadata, after ownership is acquired.
    task=claim['task']; stop=threading.Event();lost=threading.Event()
    def renew():
        while not stop.wait(max(1,claim['leaseSeconds']/3)):
            try: callback(task,'heartbeat')
            except Exception: lost.set();return
    heartbeat=threading.Thread(target=renew,name='ingestion-heartbeat',daemon=True);heartbeat.start()
    try:
        sections=parse_document(read_object(task['objectKey']),claim['mediaType'])
        if lost.is_set(): raise StaleAttempt()
        index_document(task,sections)
        callback(task,'complete')
    except StaleAttempt: pass
    except urllib.error.HTTPError as error:
        if error.code!=409: callback(task,'fail',code='INGESTION_UNAVAILABLE',permanent=False)
    except ParseError as error:
        callback(task,'fail',code=error.code,permanent=error.code!='PARSER_BUSY')
    except Exception:
        callback(task,'fail',code='INGESTION_UNAVAILABLE',permanent=False)
    finally: stop.set();heartbeat.join(timeout=6)


def consume(stop: threading.Event):
    name='worker-'+str(uuid.uuid4())
    with redis.Redis.from_url(os.environ.get('REDIS_URL','redis://redis:6379/0'),decode_responses=True,socket_timeout=5,socket_connect_timeout=2) as queue:
        while not stop.is_set():
            try:
                try: queue.xgroup_create(STREAM,GROUP,id='0',mkstream=True)
                except redis.ResponseError as error:
                    if 'BUSYGROUP' not in str(error): raise
                recovered=queue.xautoclaim(STREAM,GROUP,name,60000,count=10)[1]
                messages=recovered
                if not messages:
                    batches=queue.xreadgroup(GROUP,name,{STREAM:'>'},count=1,block=1000)
                    messages=batches[0][1] if batches else []
                for message_id,fields in messages:
                    process_message(fields['payload'])
                    queue.xack(STREAM,GROUP,message_id)
                    queue.xdel(STREAM,message_id)
            except Exception as error:
                # Do not acknowledge before the durable owner accepts completion/failure.
                LOG.warning('Ingestion delivery deferred: %s',type(error).__name__)
                stop.wait(1)
