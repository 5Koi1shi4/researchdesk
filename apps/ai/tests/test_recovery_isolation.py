from types import SimpleNamespace
import pytest


def test_recovery_refuses_shared_project_before_docker(monkeypatch):
    import test_worker_recovery as recovery
    monkeypatch.setattr(recovery.subprocess, 'run', lambda *a, **k: SimpleNamespace(returncode=0, stdout='', stderr=''))
    monkeypatch.setenv('COMPOSE_PROJECT_NAME', 'researchdesk')
    with pytest.raises(ValueError, match='isolated|shared'):
        recovery.compose('stop', 'ai')


def test_recovery_refuses_main_acceptance_port(monkeypatch):
    import test_worker_recovery as recovery
    monkeypatch.setattr(recovery.subprocess, 'run', lambda *a, **k: SimpleNamespace(returncode=0, stdout='', stderr=''))
    monkeypatch.setenv('COMPOSE_PROJECT_NAME', 'researchdesk-delivery')
    monkeypatch.setenv('ACCEPTANCE_URL', 'http://localhost:18080')
    with pytest.raises(ValueError, match='18080'):
        recovery.compose('stop', 'ai')

@pytest.mark.parametrize('url', ['http://localhost:18081', 'https://example.com:18085', 'http://localhost:18085/api'])
def test_delivery_rejects_other_targets(monkeypatch, url):
    import test_worker_recovery as recovery
    monkeypatch.setenv('COMPOSE_PROJECT_NAME', 'researchdesk-delivery')
    monkeypatch.setenv('ACCEPTANCE_URL', url)
    with pytest.raises(ValueError, match='isolated|shared'):
        recovery.compose_command('stop', 'ai')


@pytest.mark.parametrize('port', range(18080,18086))
def test_test_projects_reject_reserved_ports(monkeypatch, port):
    import test_worker_recovery as recovery
    monkeypatch.setenv('COMPOSE_PROJECT_NAME', 'researchdesk-test-safety')
    monkeypatch.setenv('ACCEPTANCE_URL', f'http://127.0.0.1:{port}')
    with pytest.raises(ValueError): recovery.compose_command('stop', 'ai')


def test_recovery_checks_target_before_any_http_write(monkeypatch):
    import test_worker_recovery as recovery
    monkeypatch.setenv('COMPOSE_PROJECT_NAME', 'researchdesk')
    monkeypatch.setenv('ACCEPTANCE_URL', 'http://127.0.0.1:18080')
    def unexpected_http(*args, **kwargs): raise AssertionError('HTTP executed before guard')
    monkeypatch.setattr(recovery.urllib.request, 'urlopen', unexpected_http)
    with pytest.raises(ValueError): recovery.test_real_worker_rebuilds_lost_queue_and_recovers_storage_outage()


def test_recovery_rejects_proxy_binding_mismatch(monkeypatch):
    import test_worker_recovery as recovery
    monkeypatch.setenv('COMPOSE_PROJECT_NAME', 'researchdesk-test-safety')
    monkeypatch.setenv('ACCEPTANCE_URL', 'http://127.0.0.1:18086')
    monkeypatch.setattr(recovery, 'compose', lambda *a: '127.0.0.1:18087')
    with pytest.raises(ValueError, match='binding'): recovery.assert_live_target()
