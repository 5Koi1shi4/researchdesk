"""Real S3 expiry regression; run only against the disposable multipart-check service."""
import datetime, hashlib, hmac, time, urllib.request, urllib.parse, urllib.error
import xml.etree.ElementTree as ET
HOST='minio-multipart-check:9000'
BUCKET='researchdesk-documents'
NS={'s':'http://s3.amazonaws.com/doc/2006-03-01/'}
def request(method,path,query='',data=b''):
 date=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ');day=date[:8]
 digest=hashlib.sha256(data).hexdigest();scope=day+'/us-east-1/s3/aws4_request'
 headers=f'host:{HOST}\nx-amz-content-sha256:{digest}\nx-amz-date:{date}\n';signed='host;x-amz-content-sha256;x-amz-date'
 canonical=f'{method}\n{path}\n{query}\n{headers}\n{signed}\n{digest}'
 key=b'AWS4multipart-test-only-password'
 for value in (day,'us-east-1','s3','aws4_request'):key=hmac.new(key,value.encode(),hashlib.sha256).digest()
 sig=hmac.new(key,('AWS4-HMAC-SHA256\n'+date+'\n'+scope+'\n'+hashlib.sha256(canonical.encode()).hexdigest()).encode(),hashlib.sha256).hexdigest()
 auth=f'AWS4-HMAC-SHA256 Credential=multipart-test/{scope}, SignedHeaders={signed}, Signature={sig}'
 req=urllib.request.Request(f'http://{HOST}{path}'+('?' + query if query else ''),data=data,method=method,headers={'Authorization':auth,'X-Amz-Date':date,'X-Amz-Content-Sha256':digest})
 with urllib.request.urlopen(req,timeout=5) as response:return response.read()
def uploads():
 return [v.text for v in ET.fromstring(request('GET','/'+BUCKET,'uploads=')).findall('s:Upload/s:Key',NS)]
for _ in range(30):
 try:request('PUT','/'+BUCKET);break
 except (urllib.error.URLError,TimeoutError):time.sleep(1)
else:raise AssertionError('Test MinIO failed to become ready')
request('PUT','/'+BUCKET+'/complete',data=b'completed-object-retained')
old_id=ET.fromstring(request('POST','/'+BUCKET+'/old','uploads=')).find('s:UploadId',NS).text
old_query='uploadId='+urllib.parse.quote(old_id,safe='')
request('PUT','/'+BUCKET+'/old','partNumber=1&'+old_query,data=b'old-part-data')
assert 'old' in uploads(), 'New multipart upload was not retained'
def expired():
 try:
  request('GET','/'+BUCKET+'/old',old_query)
  return False
 except urllib.error.HTTPError as error:
  body=error.read()
  assert error.code==404 and ET.fromstring(body).findtext('Code')=='NoSuchUpload', (error.code,body)
  return True
deadline=time.monotonic()+30
while not expired() and time.monotonic()<deadline:time.sleep(0.5)
assert expired(), 'Expired multipart data remained accessible'
young_id=ET.fromstring(request('POST','/'+BUCKET+'/young','uploads=')).find('s:UploadId',NS).text
request('PUT','/'+BUCKET+'/young','partNumber=1&uploadId='+urllib.parse.quote(young_id,safe=''),data=b'young-part-data')
assert 'young' in uploads(), 'Fresh multipart upload was removed early'
parts=ET.fromstring(request('GET','/'+BUCKET+'/young','uploadId='+urllib.parse.quote(young_id,safe='')))
assert parts.find('s:Part/s:PartNumber',NS).text=='1', 'Fresh part was not retained'
assert request('GET','/'+BUCKET+'/complete')==b'completed-object-retained'
print('PASS: expired multipart parts return NoSuchUpload; fresh multipart parts and completed object retained')
