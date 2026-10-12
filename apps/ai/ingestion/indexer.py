"""Demo vector index. Publication is atomic and bound to Java's current lease/fence."""
import hashlib
import math
import os
from typing import Protocol
import uuid
import psycopg

EMBEDDING_MODEL = 'deterministic-demo-sha256-v1'
EMBEDDING_DIMENSIONS = 32
INDEX_VERSION = 'sections-v1'


class EmbeddingAdapter(Protocol):
    model: str
    dimensions: int
    def embed(self, text: str) -> list[float]: ...


class DemoEmbedding:
    model = EMBEDDING_MODEL
    dimensions = EMBEDDING_DIMENSIONS
    def embed(self, text):
        values=[(byte-127.5)/127.5 for byte in hashlib.sha256(text.encode('utf-8')).digest()]
        norm=math.sqrt(sum(value*value for value in values))
        return [value/norm for value in values]


def embed(text):
    return DemoEmbedding().embed(text)


class StaleAttempt(Exception): pass
class IncompatibleEmbedding(Exception): pass


def index_document(task, sections, adapter: EmbeddingAdapter | None = None):
    adapter=adapter or DemoEmbedding()
    if adapter.model != EMBEDDING_MODEL or adapter.dimensions != EMBEDDING_DIMENSIONS:
        raise IncompatibleEmbedding('Reindex with a new index version before changing embedding space')
    chunks=[]
    for section in sections:
        for start in range(0,len(section.text),1000):
            text=section.text[start:start+1000].strip()
            if text: chunks.append((text,section.page,section.paragraph))
    if not chunks or len(chunks)>25000: raise ValueError('Invalid chunk count')
    # Compute before opening a transaction; no network/model calls while holding publication locks.
    vectors=[adapter.embed(text) for text,_,_ in chunks]
    if any(len(vector)!=32 or not all(math.isfinite(v) for v in vector) for vector in vectors): raise IncompatibleEmbedding('Invalid embedding dimensions or values')
    with psycopg.connect(os.environ['DATABASE_URL'],connect_timeout=2,options='-c statement_timeout=10000 -c lock_timeout=3000') as connection:
        version=str(task['documentVersionId']);fence=int(task['fence']);attempt=str(task['attemptId'])
        row=connection.execute('SELECT active AND lease_until>clock_timestamp(),fence,attempt_id,embedding_model,embedding_dimensions,index_version FROM retrieval.index_versions WHERE document_version_id=%s FOR UPDATE',(version,)).fetchone()
        if not row or not row[0] or row[1]!=fence or str(row[2])!=attempt: raise StaleAttempt()
        if row[3:] != (adapter.model,adapter.dimensions,INDEX_VERSION): raise IncompatibleEmbedding('Index version requires reindexing')
        connection.execute('UPDATE retrieval.index_versions SET published=false,chunk_count=0 WHERE document_version_id=%s',(version,))
        connection.execute('DELETE FROM retrieval.chunks WHERE document_version_id=%s',(version,))
        with connection.cursor() as cursor:
            cursor.executemany('INSERT INTO retrieval.chunks(document_version_id,fence,ordinal,chunk_id,text,page,paragraph,embedding,embedding_model) VALUES (%s,%s,%s,%s,%s,%s,%s,%s::vector,%s)',[(version,fence,number,str(uuid.uuid5(uuid.UUID(version),str(number))),text,page,paragraph,'['+','.join(str(v) for v in vector)+']',adapter.model) for number,((text,page,paragraph),vector) in enumerate(zip(chunks,vectors))])
        count=connection.execute('SELECT count(*) FROM retrieval.chunks WHERE document_version_id=%s AND fence=%s',(version,fence)).fetchone()[0]
        if count!=len(chunks): raise RuntimeError('Incomplete index')
        changed=connection.execute('UPDATE retrieval.index_versions SET published=true,chunk_count=%s,published_at=clock_timestamp() WHERE document_version_id=%s AND active AND fence=%s AND attempt_id=%s AND lease_until>clock_timestamp()',(count,version,fence,attempt)).rowcount
        if changed!=1: raise StaleAttempt()
    return len(chunks)
