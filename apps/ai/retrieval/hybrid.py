"""Bounded lexical retrieval and reciprocal rank fusion (RRF k=60)."""
import re
from dataclasses import replace
from .types import Evidence


def terms(query: str) -> list[str]:
    tokens=[]
    for token in re.findall(r'[\u3400-\u9fff]+|[a-z0-9_]+',query.lower()):
        if '\u3400' <= token[0] <= '\u9fff' and len(token)>1:
            tokens.extend(token[i:i+2] for i in range(len(token)-1))
        else:
            tokens.append(token)
    return list(dict.fromkeys(tokens))[:32]


def fuse(vector: list[Evidence], keyword: list[Evidence]) -> list[Evidence]:
    scores={}
    items={}
    for ranking in (vector,keyword):
        seen=set()
        for position,item in enumerate(ranking[:20],1):
            if item.chunk_id in seen:
                continue
            seen.add(item.chunk_id)
            scores[item.chunk_id]=scores.get(item.chunk_id,0.0)+1/(60+position)
            items.setdefault(item.chunk_id,item)
    return [replace(items[key],score=scores[key]) for key in sorted(scores,key=lambda key:(-scores[key],key))[:20]]
