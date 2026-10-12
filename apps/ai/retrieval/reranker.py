"""Optional deterministic lexical reranker; not a trained semantic model."""
from .hybrid import terms
from .types import Evidence


def rerank(query: str, candidates: list[Evidence]) -> list[Evidence]:
    tokens=terms(query)
    # RRF/vector score is the stable tie breaker. Preserve its meaning in Evidence.
    return sorted(candidates[:20],key=lambda e:(-sum(t in e.text.lower() for t in tokens),-e.score,e.chunk_id))
