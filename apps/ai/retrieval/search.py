"""Exact vector baseline; filtering is materialized before distance/ranking."""
import math
import os
from uuid import UUID
import psycopg
from ingestion.indexer import DemoEmbedding, EMBEDDING_MODEL, EMBEDDING_DIMENSIONS, INDEX_VERSION, IncompatibleEmbedding
from .types import Evidence
from .hybrid import fuse, terms
from .reranker import rerank as lexical_rerank

CANDIDATE_LIMIT = 20
CONTEXT_LIMIT = 8

SCOPED = '''WITH scoped AS MATERIALIZED (
 SELECT c.chunk_id,c.document_version_id,c.text,c.page,c.paragraph,c.embedding
 FROM retrieval.chunks c
 JOIN retrieval.index_versions i ON i.document_version_id=c.document_version_id AND i.fence=c.fence
 JOIN retrieval.ready_versions r ON r.document_version_id=i.document_version_id AND r.project_id=i.project_id
 WHERE i.project_id=%(project)s::uuid AND i.document_version_id=ANY(%(versions)s::uuid[])
 AND i.active AND i.published AND i.chunk_count>0
 AND i.embedding_model=%(model)s AND c.embedding_model=%(model)s
 AND i.embedding_dimensions=%(dimensions)s AND i.index_version=%(index)s
) '''
VECTOR_SQL = SCOPED + '''SELECT chunk_id,document_version_id,text,page,paragraph,
 1-(embedding <=> %(vector)s::vector) AS score FROM scoped
 ORDER BY embedding <=> %(vector)s::vector,chunk_id LIMIT %(count)s'''

KEYWORD_SQL = SCOPED + """SELECT chunk_id,document_version_id,text,page,paragraph,
 (SELECT count(*) FROM unnest(%(terms)s::text[]) AS token WHERE strpos(lower(text),token)>0) AS score
 FROM scoped WHERE EXISTS(SELECT 1 FROM unnest(%(terms)s::text[]) AS token WHERE strpos(lower(text),token)>0)
 ORDER BY score DESC,chunk_id LIMIT %(count)s"""


def _evidence(rows):
    return [Evidence(str(r[0]),str(r[1]),r[2],r[3],r[4],float(r[5])) for r in rows]


class RetrievalService:
    def __init__(self, *, adapter=None, database_url=None, mode="vector", rerank=False):
        if mode not in ("vector", "hybrid"):
            raise ValueError("mode must be vector or hybrid")
        self.mode = mode
        self.rerank = rerank
        self.adapter = adapter or DemoEmbedding()
        self.database_url = database_url

    def retrieve(self, project_id: str, allowed_version_ids: list[str], query: str, limit: int = 8) -> list[Evidence]:
        if type(limit) is not int or not 1 <= limit <= CONTEXT_LIMIT:
            raise ValueError('limit must be between 1 and 8')
        if not allowed_version_ids:
            return []
        if not isinstance(query,str) or not query.strip():
            return []
        if len(query)>16000 or len(allowed_version_ids)>50:
            raise ValueError('query or scope exceeds bounded retrieval budget')
        project=str(UUID(project_id))
        versions=sorted({str(UUID(v)) for v in allowed_version_ids})
        adapter=self.adapter
        if (adapter.model,adapter.dimensions)!=(EMBEDDING_MODEL,EMBEDDING_DIMENSIONS):
            raise IncompatibleEmbedding('Current index is demo/32; rebuild index before changing embedding space')
        vector=adapter.embed(query)
        if len(vector)!=adapter.dimensions or not all(math.isfinite(v) for v in vector) or not any(vector):
            raise IncompatibleEmbedding('Invalid query embedding')
        params=dict(project=project,versions=versions,model=adapter.model,dimensions=adapter.dimensions,
                    index=INDEX_VERSION,vector=str(vector),count=10 if self.mode=='hybrid' else CANDIDATE_LIMIT,terms=terms(query))
        with psycopg.connect(self.database_url or os.environ['DATABASE_URL'],connect_timeout=2,
                             options='-c statement_timeout=5000 -c lock_timeout=1000') as conn:
            conn.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY')
            results=_evidence(conn.execute(VECTOR_SQL,params).fetchall())
            if self.mode=='hybrid':
                keyword=_evidence(conn.execute(KEYWORD_SQL,params).fetchall())
                results=fuse(results,keyword)
        if self.rerank:
            results=lexical_rerank(query,results)
        return results[:limit]


def retrieve(project_id: str, allowed_version_ids: list[str], query: str, limit: int = 8) -> list[Evidence]:
    return RetrievalService().retrieve(project_id,allowed_version_ids,query,limit)
