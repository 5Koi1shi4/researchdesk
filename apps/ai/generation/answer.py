"""Grounded answer orchestration against the frozen retrieval interface."""
import time
from .provider import ProviderError, configured_provider, check_deadline
from .prompts import build_messages
from .citations import validate_citations

def retrieve(project_id, allowed_version_ids, query, limit=8):
    from retrieval import retrieve as scoped_retrieve
    return scoped_retrieve(project_id, allowed_version_ids, query, limit=limit)

def stream_answer(*, project_id, allowed_version_ids, prompt, history, titles,
                  deadline=None, provider=None, retriever=None, cancelled=None, telemetry=None):
    deadline = deadline or time.monotonic()+120
    def check():
        check_deadline(deadline)
        if cancelled and cancelled.is_set(): raise ProviderError('GENERATION_CANCELLED')
    check()
    retrieval_start = time.monotonic()
    evidence = (retriever or retrieve)(project_id,list(allowed_version_ids),prompt,limit=8) if allowed_version_ids else []
    if telemetry is not None: telemetry['retrievalMs'] = int((time.monotonic()-retrieval_start)*1000)
    check()
    if any(e.document_version_id not in allowed_version_ids for e in evidence):
        raise ProviderError('EVIDENCE_SCOPE_INVALID')
    if len({e.chunk_id for e in evidence}) != len(evidence): raise ProviderError('EVIDENCE_SCOPE_INVALID')
    if not evidence:
        yield 'delta', {'text':'资料不足：当前授权资料中未检索到可支持回答的证据。请补充资料或调整问题。'}
        yield 'completed', {'state':'COMPLETED'}
        return
    provider = provider or configured_provider()
    if telemetry is not None: telemetry.update(getattr(provider,'usage',{}))
    chunks=[]
    provider_start = time.monotonic()
    for text in provider.stream(build_messages(prompt,evidence,history),max_tokens=2000,deadline=deadline):
        check()
        if not isinstance(text,str): raise ProviderError('PROVIDER_PROTOCOL_ERROR')
        if telemetry is not None and not chunks: telemetry['ttftMs'] = int((time.monotonic()-provider_start)*1000)
        chunks.append(text)
        if sum(map(len,chunks))>32000: raise ProviderError('PROVIDER_OUTPUT_INCOMPLETE')
        yield 'delta', {'text':text}
    check()
    if telemetry is not None: telemetry.update(getattr(provider,'usage',{}))
    text=''.join(chunks)
    if not text.strip(): raise ProviderError('PROVIDER_OUTPUT_INCOMPLETE')
    for citation in validate_citations(text,evidence,titles):
        yield 'citation', {'citation':citation}
    yield 'completed', {'state':'COMPLETED'}
