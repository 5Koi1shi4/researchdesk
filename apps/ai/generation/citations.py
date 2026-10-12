"""Validate marker membership; this does not assert semantic support."""
import re
from generated.contracts import Citation
from .provider import ProviderError
MARKER = re.compile(r'\[\[cite:([^\[\]\s]+)\]\]')
def validate_citations(text, evidence, titles):
    found = MARKER.findall(text)
    if '[[cite:' in MARKER.sub('',text): raise ProviderError('INVALID_CITATION')
    if not found: raise ProviderError('MISSING_CITATION')
    allowed = {e.chunk_id:e for e in evidence}
    result=[]
    for identifier in dict.fromkeys(found):
        if identifier not in allowed: raise ProviderError('INVALID_CITATION')
        item = allowed[identifier]
        result.append(Citation.model_validate({'chunkId':identifier,'documentVersionId':item.document_version_id,
            'title':titles.get(item.document_version_id,'资料'), 'page':item.page,'paragraph':item.paragraph,
            'excerpt':item.text[:1000],'availability':'AVAILABLE'}).model_dump(mode='json'))
    return result
