import importlib.util
import pytest


def modules():
    assert importlib.util.find_spec('evals.score'), 'evaluation scorer missing'
    from evals.score import score
    return score


def test_hand_calculated_recall_and_semantic_citations():
    score = modules()
    result = score([
        {'gold_chunk_ids':['a'], 'retrieved_chunk_ids':['a'], 'citations':[{'exists':True,'supported':False}], 'latency_ms':10},
        {'gold_chunk_ids':['b'], 'retrieved_chunk_ids':['x'], 'citations':[{'exists':True,'supported':True}], 'latency_ms':30},
    ])
    assert result['recall_at_5'] == 0.5
    assert result['citation_support_accuracy'] == 0.5
    assert result['total_tokens'] is None
    assert result['latency_ms_mean'] == 20


def test_unreviewed_quality_stays_unknown_and_partial_usage_is_unknown():
    result = modules()([
        {'gold_chunk_ids':[], 'retrieved_chunk_ids':[], 'latency_ms':1,'usage':{'total_tokens':12}},
        {'gold_chunk_ids':['a','b'],'retrieved_chunk_ids':['a'], 'latency_ms':3},
    ])
    assert result['recall_at_5'] == 0.5
    assert result['citation_support_accuracy'] is None
    assert result['refusal_accuracy'] is None
    assert result['task_completion'] is None
    assert result['total_tokens'] is None


def test_all_annotation_metrics_and_actual_usage():
    result = modules()([{'gold_chunk_ids':['a'],'retrieved_chunk_ids':['a'], 'latency_ms':4,
        'usage':{'total_tokens':15}, 'citations':[{'exists':False,'supported':True}],
        'key_claims':2,'supported_cited_claims':1,'expected_refusal':True,'correct_refusal':True,'task_completion':0.5}])
    assert result['citation_support_accuracy'] == 0
    assert result['key_claim_citation_coverage'] == 0.5
    assert result['refusal_accuracy'] == 1
    assert result['task_completion'] == 0.5
    assert result['total_tokens'] == 15


def test_invalid_annotation_rejected():
    with pytest.raises(ValueError):
        modules()([{'gold_chunk_ids':[], 'retrieved_chunk_ids':[], 'latency_ms':0, 'key_claims':1,'supported_cited_claims':2}])


def runner():
    assert importlib.util.find_spec('evals.run'), 'evaluation runner missing'
    from evals import run
    return run


def test_real_missing_configuration_fails_without_demo_fallback(monkeypatch):
    for name in ('OPENAI_BASE_URL','OPENAI_API_KEY','OPENAI_MODEL'):
        monkeypatch.delenv(name, raising=False)
    with pytest.raises(ValueError, match='configuration'):
        runner().real_settings()


def test_holdout_locked_before_any_provider_call():
    with pytest.raises(ValueError, match='holdout'):
        runner().validate_run('holdout','real',{})


def test_config_rejects_secrets_and_unknown_retrieval_mode():
    with pytest.raises(ValueError):
        runner().validate_run('dev','stub',{'api_key':'must-not-persist'})
    with pytest.raises(ValueError):
        runner().validate_run('dev','stub',{'retrieval_mode':'magic'})


def test_fixture_retrieval_filters_before_ranking():
    run=runner()
    chunks=[{'chunk_id':name,'document_version_id':version,'project_id':project,'text':'budget','status':status,'page':None,'paragraph':1} for name,version,project,status in [('ok','v1','p1','READY'),('private','v2','p2','READY'),('deleted','v3','p1','DELETED'),('unselected','v4','p1','READY')]]
    for mode in ('vector','hybrid'):
        evidence=run.fixture_retrieve({'project_id':'p1','allowed_version_ids':['v1','v2','v3'],'question':'budget'},chunks,run.validate_run('dev','stub',{'retrieval_mode':mode}))
        assert [e.chunk_id for e in evidence]==['ok']


@pytest.fixture
def local_provider():
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    import threading
    import json
    state={'status':200,'body':{'choices':[{'message':{'content':'local test answer'}}]},'requests':[]}
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            state['requests'].append({'path':self.path,'body':json.loads(self.rfile.read(int(self.headers['Content-Length']))),'authorization':self.headers['Authorization']})
            self.send_response(state['status'])
            self.send_header('Content-Type','application/json')
            self.end_headers()
            self.wfile.write(json.dumps(state['body']).encode())
        def log_message(self,*args): pass
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
    thread=threading.Thread(target=server.serve_forever,daemon=True)
    thread.start()
    state['url']=f'http://127.0.0.1:{server.server_port}/v1'
    yield state
    server.shutdown()
    server.server_close()
    thread.join()


def test_real_adapter_http_wire_and_unknown_usage(local_provider):
    run=runner()
    settings={'base_url':local_provider['url'],'api_key':'test-only','model':'local-test-model'}
    answer,usage=run.real_answer(settings,'Question',[],run.DEFAULT_CONFIG)
    assert answer=='local test answer'
    assert usage=={'total_tokens':None}
    request=local_provider['requests'][0]
    assert request['path']=='/v1/chat/completions'
    assert request['body']['model']=='local-test-model'
    assert request['body']['stream'] is False
    assert request['body']['max_tokens']==2000
    assert request['authorization']=='Bearer test-only'
    assert len(request['body']['messages'])==2


def test_real_adapter_preserves_usage_and_redacts_failures(local_provider):
    run=runner()
    settings={'base_url':local_provider['url'],'api_key':'secret-test-only','model':'test'}
    local_provider['body']['usage']={'total_tokens':42}
    assert run.real_answer(settings,'q',[],run.DEFAULT_CONFIG)[1]=={'total_tokens':42}
    local_provider['status']=500
    local_provider['body']={'error':'secret-test-only upstream confidential error'}
    with pytest.raises(RuntimeError) as failure:
        run.real_answer(settings,'q',[],run.DEFAULT_CONFIG)
    assert 'secret-test-only' not in str(failure.value)
    assert 'no fallback' in str(failure.value)
    assert len(local_provider['requests'])==2


def test_real_adapter_rejects_empty_message(local_provider):
    run=runner()
    local_provider['body']={'choices':[{'message':{'content':''}}]}
    with pytest.raises(RuntimeError):
        run.real_answer({'base_url':local_provider['url'],'api_key':'test','model':'test'},'q',[],run.DEFAULT_CONFIG)


def test_human_reviews_require_exact_run_and_complete_question_coverage():
    from evals import score as scorer
    assert hasattr(scorer,'review_results'), 'bound human review import missing'
    report={'results':[{'id':'q1','gold_chunk_ids':['a'],'retrieved_chunk_ids':['a'],'latency_ms':1,'answer':'[[cite:a]]','expected_refusal':False}]}
    with pytest.raises(ValueError):
        scorer.review_results(report,{'run_sha256':'wrong','reviews':[]},'correct')
    with pytest.raises(ValueError):
        scorer.review_results(report,{'run_sha256':'correct','reviews':[]},'correct')
    annotated=scorer.review_results(report,{'run_sha256':'correct','reviewer':'reviewer-A','reviews':[{'id':'q1','citations':[{'chunk_id':'a','supported':False}],'key_claims':1,'supported_cited_claims':0,'task_completion':0}]},'correct')
    assert annotated['citation_support_accuracy']==0


def test_holdout_release_binds_review_freeze_and_configuration_once(tmp_path):
    import json
    run=runner()
    assert hasattr(run,'reserve_holdout'), 'explicit reviewed one-shot holdout release missing'
    release=tmp_path/'release.json'
    ledger=tmp_path/'holdout.lock'
    release.write_text(json.dumps({'user_authorized':True,'authorized_by':'test-user','protocol_reviewed':True,'reviewer':'independent-test-reviewer','freeze_sha256':'frozen','config_sha256':'configured'}))
    with pytest.raises(ValueError):
        run.reserve_holdout(release,ledger,'changed','configured','real')
    with pytest.raises(ValueError):
        run.reserve_holdout(release,ledger,'frozen','configured','stub')
    assert not ledger.exists()
    run.reserve_holdout(release,ledger,'frozen','configured','real')
    assert ledger.exists()
    with pytest.raises(ValueError,match='already'):
        run.reserve_holdout(release,ledger,'frozen','configured','real')


def test_frozen_runtime_dependencies_and_tamper_detection(tmp_path, monkeypatch):
    import hashlib,json
    run=runner()
    freeze=json.loads((run.ROOT/'evals/freeze.json').read_text(encoding='utf-8-sig'))
    assert {'apps/ai/ingestion/indexer.py','apps/ai/retrieval/hybrid.py','apps/ai/retrieval/reranker.py','apps/ai/retrieval/types.py'} <= freeze['sha256'].keys()
    (tmp_path/'evals').mkdir()
    artifact=tmp_path/'dependency.py'
    artifact.write_bytes(b'original')
    (tmp_path/'evals/freeze.json').write_text(json.dumps({'sha256':{'dependency.py':hashlib.sha256(b'original').hexdigest()}}))
    monkeypatch.setattr(run,'ROOT',tmp_path)
    run.verify_freeze()
    artifact.write_bytes(b'changed')
    with pytest.raises(ValueError,match='artifact changed'):
        run.verify_freeze()


def test_release_configuration_hash_changes_with_model_and_endpoint():
    run=runner()
    assert hasattr(run,'run_config'), 'release and output need shared resolved configuration'
    config=run.validate_run('dev','real',{})
    first=run.run_config('holdout','real',config,{'model':'model-a','base_url':'https://one.example/v1'})
    second=run.run_config('holdout','real',config,{'model':'model-b','base_url':'https://one.example/v1'})
    third=run.run_config('holdout','real',config,{'model':'model-a','base_url':'https://two.example/v1'})
    assert first!=second and first!=third
    assert first['model']=='model-a'
    assert first['split']=='holdout'
    assert first['embedding_dimensions']==32
    assert first['provider_base_url_sha256']!='https://one.example/v1'


def test_human_review_cannot_omit_or_forge_citation_occurrences():
    from evals.score import review_results
    report={'results':[{'id':'q1','gold_chunk_ids':['a'],'retrieved_chunk_ids':['a'],'latency_ms':1,'answer':'one [[cite:a]] two [[cite:missing]] again [[cite:a]]','expected_refusal':False}]}
    review={'id':'q1','citations':[{'chunk_id':'a','exists':True,'supported':True}],'key_claims':3,'supported_cited_claims':2,'task_completion':0.5}
    annotation={'run_sha256':'h','reviewer':'person','reviews':[review]}
    with pytest.raises(ValueError,match='citation'):
        review_results(report,annotation,'h')
    review['citations']=[{'chunk_id':'a','supported':True},{'chunk_id':'missing','supported':True},{'chunk_id':'a','supported':False}]
    assert review_results(report,annotation,'h')['citation_support_accuracy']==pytest.approx(1/3)
    review['citations'][1]['exists']=True
    with pytest.raises(ValueError,match='citation'):
        review_results(report,annotation,'h')
