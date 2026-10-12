import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import pytest
from fastapi.testclient import TestClient
from app import app
from generation.provider import OpenAICompatibleProvider, ProviderError

@pytest.fixture
def server():
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_POST(self):
            self.server.received=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            if getattr(self.server,'redirect',None):
                self.send_response(302);self.send_header('Location',self.server.redirect);self.end_headers();return
            self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
            time.sleep(getattr(self.server,'delay',0))
            self.wfile.write(self.server.body)
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
    server.body=b'data: {"choices":[{"delta":{"content":"hello"},"finish_reason":null}]}\n\ndata: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n'
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    yield server
    server.shutdown();thread.join();server.server_close()

def test_real_adapter_sends_limits_and_parses_network_stream(server):
    provider=OpenAICompatibleProvider('http://127.0.0.1:'+str(server.server_port)+'/v1','test-model','not-a-real-key')
    assert list(provider.stream([{'role':'user','content':'q'}],2000,time.monotonic()+5))==['hello']
    assert server.received['max_tokens']==2000
    assert server.received['stream'] is True

@pytest.mark.parametrize('body',[b'data: not-json\n',b'data: {"choices":[{"delta":{"content":"partial"},"finish_reason":"length"}]}\n',b'data: {"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}\n'])
def test_real_adapter_rejects_invalid_or_truncated_stream(server,body):
    server.body=body
    provider=OpenAICompatibleProvider('http://127.0.0.1:'+str(server.server_port),'test','fake')
    with pytest.raises(ProviderError): list(provider.stream([],2000,time.monotonic()+5))

def request():
    return {'runId':'run-1','kind':'ANSWER','projectId':'project-1','documentVersionIds':[],'prompt':'question','history':[],'titles':{}}

def test_internal_route_rejects_untrusted_caller(monkeypatch):
    monkeypatch.setenv('INTERNAL_SERVICE_TOKEN','secret')
    assert TestClient(app).post('/internal/generation/stream',json=request()).status_code==401

def test_internal_route_emits_canonical_ordered_events(monkeypatch):
    from generated.contracts import RunEvent
    from generation.envelope import split_internal_event
    monkeypatch.setenv('INTERNAL_SERVICE_TOKEN','secret')
    response=TestClient(app).post('/internal/generation/stream',json=request(),headers={'Authorization':'Bearer secret'})
    assert response.status_code==200
    packets=[json.loads(line) for line in response.text.splitlines()]
    validated=[split_internal_event(packet) for packet in packets]
    events=[event.model_dump(mode='json') for event, usage in validated]
    assert validated[-1][1].mode=='DEMO'
    assert validated[-1][1].inputTokens is None
    assert validated[-1][1].durationMs >= 0
    assert all(usage is None for event,usage in validated[:-1])
    assert all('usage' not in event for event in events)
    with pytest.raises(ValueError): RunEvent.model_validate(packets[-1])
    assert [e['seq'] for e in events]==[1,2,3]
    assert [e['type'] for e in events]==['started','delta','completed']

def test_internal_provider_failure_is_sanitized(monkeypatch):
    import generation.routes as routes
    monkeypatch.setenv('INTERNAL_SERVICE_TOKEN','secret')
    def fail(**kwargs):
        raise RuntimeError('secret-api-key internal traceback')
        yield
    monkeypatch.setattr(routes,'stream_answer',fail)
    response=TestClient(app).post('/internal/generation/stream',json=request(),headers={'Authorization':'Bearer secret'})
    events=[json.loads(line) for line in response.text.splitlines()]
    assert events[-1]['type']=='failed'
    assert events[-1]['payload']['error']['code']=='GENERATION_FAILED'
    assert 'secret-api-key' not in response.text


def test_internal_missing_token_fails_closed(monkeypatch):
    monkeypatch.delenv('INTERNAL_SERVICE_TOKEN',raising=False)
    assert TestClient(app).post('/internal/generation/stream',json=request(),headers={'Authorization':'Bearer '}).status_code==401

@pytest.mark.parametrize('body',[b'data: []\n',b'data: {"choices":["wrong"]}\n'])
def test_real_adapter_rejects_wrong_json_shapes(server,body):
    server.body=body
    provider=OpenAICompatibleProvider('http://127.0.0.1:'+str(server.server_port),'test','fake')
    with pytest.raises(ProviderError,match='PROVIDER_PROTOCOL_ERROR'):
        list(provider.stream([],2000,time.monotonic()+5))


def test_real_adapter_allows_slow_first_token_inside_deadline(server):
    server.delay=6
    provider=OpenAICompatibleProvider('http://127.0.0.1:'+str(server.server_port),'test','fake')
    assert list(provider.stream([],2000,time.monotonic()+10))==['hello']

def test_provider_rejects_redirect_without_forwarding_authorization(server):
    received=[]
    class Sink(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_GET(self):
            received.append(self.headers.get('Authorization'))
            self.send_response(200);self.end_headers()
    sink=ThreadingHTTPServer(('127.0.0.1',0),Sink)
    thread=threading.Thread(target=sink.serve_forever,daemon=True);thread.start()
    server.redirect='http://127.0.0.1:'+str(sink.server_port)+'/sink'
    try:
        provider=OpenAICompatibleProvider('http://127.0.0.1:'+str(server.server_port),'test','fake-secret')
        with pytest.raises(ProviderError):list(provider.stream([],2000,time.monotonic()+10))
        assert received==[], 'Credentials must never be forwarded by a provider redirect'
    finally:
        sink.shutdown();thread.join();sink.server_close()


def test_absolute_deadline_closes_trickling_partial_sse_line():
    class Trickle(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_POST(self):
            self.rfile.read(int(self.headers['Content-Length']))
            self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
            try:
                for _ in range(20):
                    self.wfile.write(b':');self.wfile.flush();time.sleep(.05)
                self.wfile.write(b'\n')
            except (BrokenPipeError,ConnectionResetError):pass
    server=ThreadingHTTPServer(('127.0.0.1',0),Trickle)
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    try:
        provider=OpenAICompatibleProvider('http://127.0.0.1:'+str(server.server_port),'test','fake')
        started=time.perf_counter()
        with pytest.raises(ProviderError,match='GENERATION_TIMEOUT'):
            list(provider.stream([],2000,time.monotonic()+.2))
        assert time.perf_counter()-started<.5, 'Trickled bytes must not extend the absolute deadline'
    finally:server.shutdown();thread.join();server.server_close()


@pytest.mark.parametrize('change', [{'inputTokens':-1},{'inputTokens':True},{'inputTokens':'5'},{'secret':'credential'},{'mode':'UNVERIFIED'}])
def test_internal_envelope_rejects_invalid_private_metadata(change):
    from generation.envelope import split_internal_event
    usage={'provider':'demo','model':None,'mode':'DEMO','inputTokens':None,'outputTokens':None,'totalTokens':None,**change}
    with pytest.raises(ValueError):
        split_internal_event({'runId':'r','seq':1,'type':'completed','payload':{'state':'COMPLETED'},'usage':usage})


def test_internal_envelope_does_not_allow_nonterminal_usage_or_unknown_public_fields():
    from generation.envelope import split_internal_event
    usage={'provider':'demo','model':None,'mode':'DEMO','inputTokens':None,'outputTokens':None,'totalTokens':None}
    with pytest.raises(ValueError): split_internal_event({'runId':'r','seq':1,'type':'delta','payload':{'text':'hi'},'usage':usage})
    with pytest.raises(ValueError): split_internal_event({'runId':'r','seq':1,'type':'completed','payload':{'state':'COMPLETED'},'usage':usage,'secret':'bad'})
