import asyncio
from types import SimpleNamespace
import pytest
from generation.report import generate_report

class Provider:
    def __init__(self, text): self.text=text; self.calls=0
    async def stream(self, messages, max_tokens, deadline):
        self.calls += 1
        assert max_tokens == 6000
        yield self.text

def evidence():
    return SimpleNamespace(chunk_id='c1',document_version_id='v1',text='Grounded evidence',page=1,paragraph=None,score=.9)

def test_empty_evidence_never_calls_provider():
    p=Provider('bad')
    result=asyncio.run(generate_report([],p,'Question',['v1']))
    assert p.calls == 0 and result['citations'] == []

def test_canonical_stable_citations():
    result=asyncio.run(generate_report([evidence()],Provider('Fact [[cite:c1]]'),'Question',['v1']))
    assert result['markdown']=='Fact [[cite:c1]]'
    assert result['citations'][0].chunkId=='c1'

def test_fabricated_and_unauthorized_citations_rejected():
    with pytest.raises(ValueError): asyncio.run(generate_report([evidence()],Provider('[[cite:fake]]'),'Q',['v1']))
    with pytest.raises(ValueError): asyncio.run(generate_report([evidence()],Provider('[[cite:c1]]'),'Q',['other']))

def test_deadline_and_output_limit():
    with pytest.raises(TimeoutError): asyncio.run(generate_report([evidence()],Provider('[[cite:c1]]'),'Q',['v1'],deadline=0))
    with pytest.raises(ValueError): asyncio.run(generate_report([evidence()],Provider('x'*200001+'[[cite:c1]]'),'Q',['v1']))

def test_stalled_provider_deadline():
    import time
    class Stalled:
        async def stream(self,*args,**kwargs):
            await asyncio.sleep(1)
            yield '[[cite:c1]]'
    with pytest.raises(TimeoutError): asyncio.run(generate_report([evidence()],Stalled(),'Q',['v1'],deadline=time.monotonic()+.01))

@pytest.mark.parametrize('text',['[[cite:c1','[[cite:fake]]','uncited claim'])
def test_invalid_report_rejected(text):
    with pytest.raises(ValueError): asyncio.run(generate_report([evidence()],Provider(text),'Q',['v1']))

def test_sync_provider_json_evidence_contract():
    import json
    class SyncDemo:
        def stream(self,messages,max_tokens,deadline):
            data=json.loads(messages[-1]['content'])
            assert data['question']=='Q' and data['history']==[]
            yield data['evidence'][0]['text']+' [[cite:'+data['evidence'][0]['chunk_id']+']]'
    result=asyncio.run(generate_report([evidence()],SyncDemo(),'Q',['v1']))
    assert result['citations'][0].chunkId=='c1'

def test_sync_stalled_provider_deadline():
    import time
    class SyncStalled:
        def stream(self,*args,**kwargs):
            time.sleep(.2)
            yield '[[cite:c1]]'
    started=time.monotonic()
    with pytest.raises(TimeoutError): asyncio.run(generate_report([evidence()],SyncStalled(),'Q',['v1'],deadline=started+.01))
    assert time.monotonic()-started < .15
