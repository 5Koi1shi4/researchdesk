import json, threading, time
from http.server import BaseHTTPRequestHandler, HTTPServer
import pytest
from generation.provider import OpenAICompatibleProvider

@pytest.mark.parametrize('usage,expected', [(None,(None,None,None)), ({},(None,None,None)), ({'prompt_tokens':4,'completion_tokens':6,'total_tokens':10},(4,6,10)), ({'prompt_tokens':-1,'completion_tokens':'6','total_tokens':True},(None,None,None))])
def test_usage_only_packet_is_captured_and_requested(usage,expected):
    requests=[]
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_POST(self):
            requests.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
            self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
            for packet in [{'choices':[{'delta':{'content':'ok'},'finish_reason':None}]},{'choices':[{'delta':{},'finish_reason':'stop'}]},{'choices':[],'usage':usage}]:
                self.wfile.write(('data: '+json.dumps(packet)+'\n\n').encode())
            self.wfile.write(b'data: [DONE]\n\n')
    server=HTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    try:
        p=OpenAICompatibleProvider('http://127.0.0.1:'+str(server.server_port),'fixture-model','fixture-key')
        assert list(p.stream([],10,time.monotonic()+5))==['ok']
        assert requests[0].get('stream_options')=={'include_usage':True}
        assert tuple(p.usage[k] for k in ('inputTokens','outputTokens','totalTokens'))==expected
        assert p.usage['mode']=='REAL' and p.usage['model']=='fixture-model'
        assert 'fixture-key' not in json.dumps(p.usage)
    finally: server.shutdown();server.server_close();thread.join()

def test_internal_terminal_metadata_is_separate_from_public_event(monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    import generation.routes as routes
    monkeypatch.setenv('INTERNAL_SERVICE_TOKEN','local-test')
    app=FastAPI();app.include_router(routes.router)
    with TestClient(app) as client:
        response=client.post('/internal/generation/stream',headers={'Authorization':'Bearer local-test'},json={'runId':'run-1','kind':'ANSWER','projectId':'p','documentVersionIds':[],'prompt':'private question'})
    packets=[json.loads(line) for line in response.text.splitlines()]
    assert packets[-1]['payload']=={'state':'COMPLETED'}
    assert packets[-1]['usage']['inputTokens'] is None
    assert packets[-1]['usage']['durationMs'] >= 0
    assert 'private question' not in json.dumps(packets[-1])

def test_ingestion_metrics_log_only_ids_and_durations(monkeypatch,caplog):
    from worker import consumer
    task={'taskId':'task-1','attemptId':'attempt-1','fence':1,'projectId':'p','documentVersionId':'v','objectKey':'private-object','parserVersion':'v1'}
    monkeypatch.setattr(consumer,'callback',lambda task,action,**kwargs: {'task':task,'mediaType':'text/markdown','leaseSeconds':30} if action=='claim' else None)
    monkeypatch.setattr(consumer,'read_object',lambda key:b'private document body')
    monkeypatch.setattr(consumer,'parse_document',lambda *args:['private parsed text'])
    monkeypatch.setattr(consumer,'index_document',lambda *args:None)
    with caplog.at_level('INFO'): consumer.process_message(json.dumps(task))
    assert 'parseMs=' in caplog.text and 'indexMs=' in caplog.text and 'durationMs=' in caplog.text
    assert 'task-1' in caplog.text and 'private' not in caplog.text

def test_report_terminal_retains_provider_usage(monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from types import SimpleNamespace
    import generation.routes as routes
    monkeypatch.setenv('INTERNAL_SERVICE_TOKEN','local-test')
    monkeypatch.setenv('GENERATION_MODE','openai-compatible')
    class Provider:
        def __init__(self): self.usage={}
        def stream(self,*args,**kwargs):
            yield 'Supported fact [[cite:c1]]'
            self.usage={'provider':'openai-compatible','model':'fixture-model','mode':'REAL','inputTokens':4,'outputTokens':6,'totalTokens':10}
    monkeypatch.setattr(routes,'configured_provider',Provider)
    monkeypatch.setattr(routes,'retrieve',lambda *args:[SimpleNamespace(chunk_id='c1',document_version_id='v1',text='fact',page=1,paragraph=None)])
    app=FastAPI();app.include_router(routes.router)
    with TestClient(app) as client:
        response=client.post('/internal/generation/stream',headers={'Authorization':'Bearer local-test'},json={'runId':'r','kind':'REPORT','projectId':'p','documentVersionIds':['v1'],'prompt':'report'})
    terminal=json.loads(response.text.splitlines()[-1])
    assert terminal['type']=='completed'
    assert terminal['usage']['totalTokens']==10
    assert terminal['usage']['ttftMs']>=0
    assert terminal['usage']['retrievalMs']>=0
