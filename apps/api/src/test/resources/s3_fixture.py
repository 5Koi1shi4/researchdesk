"""Read-only private S3 fixture queries, executed inside the isolated AI service."""
import datetime, hashlib, hmac, os, sys, urllib.parse, urllib.request
import xml.etree.ElementTree as ET
from worker.consumer import read_object

operation, key = sys.argv[1:]
if operation == "read":
    print(read_object(key).decode("utf-8"))
elif operation == "list":
    endpoint=os.environ.get("STORAGE_ENDPOINT","http://minio:9000").rstrip("/")
    host=urllib.parse.urlsplit(endpoint).netloc
    path="/"+urllib.parse.quote(os.environ.get("STORAGE_BUCKET","researchdesk-documents"),safe="")
    query="list-type=2&prefix="+urllib.parse.quote(key,safe="")
    date=datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ");day=date[:8]
    digest=hashlib.sha256(b"").hexdigest();scope=day+"/us-east-1/s3/aws4_request"
    headers="host:"+host+"\nx-amz-content-sha256:"+digest+"\nx-amz-date:"+date+"\n"
    signed="host;x-amz-content-sha256;x-amz-date"
    canonical="GET\n"+path+"\n"+query+"\n"+headers+"\n"+signed+"\n"+digest
    secret=("AWS4"+os.environ["STORAGE_SECRET_KEY"]).encode()
    for value in (day,"us-east-1","s3","aws4_request"):
        secret=hmac.new(secret,value.encode(),hashlib.sha256).digest()
    signature=hmac.new(secret,("AWS4-HMAC-SHA256\n"+date+"\n"+scope+"\n"+hashlib.sha256(canonical.encode()).hexdigest()).encode(),hashlib.sha256).hexdigest()
    auth="AWS4-HMAC-SHA256 Credential="+os.environ["STORAGE_ACCESS_KEY"]+"/"+scope+", SignedHeaders="+signed+", Signature="+signature
    request=urllib.request.Request(endpoint+path+"?"+query,headers={"Authorization":auth,"X-Amz-Date":date,"X-Amz-Content-Sha256":digest})
    with urllib.request.urlopen(request,timeout=10) as response: root=ET.fromstring(response.read())
    assert root.findtext("{*}IsTruncated") == "false", "fixture exceeds list page; refusing partial assertion"
    for entry in root.findall("{*}Contents/{*}Key"): print(entry.text)
else:
    raise ValueError("Unsupported fixture operation")
