import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator
ROOT=Path(__file__).resolve().parents[1]
DOC=json.loads((ROOT/'contracts/openapi.json').read_text())

def test_run_endpoints_document_authorization_and_resume():
    for path,method in [('/api/runs','post'),('/api/runs/{id}','get'),('/api/runs/{id}/cancel','post'),('/api/runs/{id}/events','get')]:
        assert path in DOC['paths'], f'Missing generation endpoint {path}'
        assert DOC['paths'][path][method]['security']==[{'sessionCookie':[]}]
    stream=DOC['paths']['/api/runs/{id}/events']['get']
    assert '410' in stream['responses']
    assert any(x['name']=='Last-Event-ID' for x in stream['parameters'])

def test_snapshot_requires_explicit_mode_and_nullable_error():
    assert 'RunSnapshot' in DOC['components']['schemas']
    sample=dict(runId='r1',conversationId='c1',projectId='p1',kind='ANSWER',documentVersionIds=[],prompt='question',state='COMPLETED',text='资料不足',citations=[],lastSeq=3,mode='DEMO',error=None)
    validator=Draft202012Validator({'$ref':'#/components/schemas/RunSnapshot',**DOC})
    validator.validate(sample)
    assert not validator.is_valid({**sample,'mode':'UNKNOWN'})
    assert not validator.is_valid({**sample,'lastSeq':-1})
    missing=dict(sample);missing.pop('mode')
    assert not validator.is_valid(missing)
