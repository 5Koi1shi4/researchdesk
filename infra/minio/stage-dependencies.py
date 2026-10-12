import concurrent.futures, pathlib, urllib.request, time, tarfile, hashlib
root=pathlib.Path(__file__).parent
archive=root/'source.tar.gz'
assert hashlib.sha256(archive.read_bytes()).hexdigest() == '45521908307306e925c98d629e1c17d78c8b72b6ee242b1bfb1409f7d8ee5841'
with tarfile.open(archive) as source:
 sums=source.extractfile('minio-9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a/go.sum').read().decode().splitlines()
items=set()
for line in sums:
 m,v,_=line.split(); mod=v.endswith('/go.mod'); v=v.removesuffix('/go.mod')
 escaped=''.join('!'+c.lower() if c.isupper() else c for c in m)
 for ext in (['mod'] if mod else ['mod','info','zip']): items.add(f'{escaped}/@v/{v}.{ext}')
def fetch(p):
 target=root/'gomod-cache'/p
 if target.exists():return
 target.parent.mkdir(parents=True,exist_ok=True)
 for attempt in range(4):
  try:
   with urllib.request.urlopen('https://proxy.golang.org/'+p,timeout=90) as response: data=response.read()
   target.write_bytes(data);return
  except Exception:
   if attempt==3:raise
   time.sleep(2)
with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
 for i,_ in enumerate(pool.map(fetch,sorted(items)),1):
  if i%100==0:print(f'{i}/{len(items)} dependencies staged',flush=True)
print('Dependencies staged',flush=True)
