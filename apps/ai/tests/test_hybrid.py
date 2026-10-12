from retrieval.types import Evidence


def item(id,score=1.0,text='alpha beta'):
    return Evidence(id,'version',text,None,1,score)


def test_rrf_combines_ranks_without_double_counting_and_caps_candidates():
    from retrieval.hybrid import fuse
    result=fuse([item('a'),item('b')],[item('b'),item('c'),item('c')])
    assert [e.chunk_id for e in result]==['b','a','c']
    assert result[0].score == 1/62+1/61
    assert len(fuse([item(str(i)) for i in range(30)],[]))==20


def test_reranker_improves_lexical_relevance_preserving_evidence_ids():
    from retrieval.reranker import rerank
    result=rerank('alpha',[item('a',text='unrelated'),item('b',text='alpha beta')])
    assert [e.chunk_id for e in result]==['b','a']
    assert result[0].text=='alpha beta'


def test_multilingual_tokenizer_bounded_and_deterministic():
    from retrieval.hybrid import terms
    assert terms('退款规则 refund REFUND')==['退款','款规','规则','refund']
    assert len(terms(' '.join('word'+str(i) for i in range(100))))==32
