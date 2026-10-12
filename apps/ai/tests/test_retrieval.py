import importlib.util
import pytest


def test_scoped_retrieval_package_exists():
    assert importlib.util.find_spec('retrieval.search') is not None, 'scoped retrieval missing'


def test_empty_authorization_never_connects(monkeypatch):
    from retrieval.search import retrieve
    monkeypatch.delenv('DATABASE_URL', raising=False)
    assert retrieve('not-used', [], 'anything') == []


@pytest.mark.parametrize('limit', [0, 9, -1, True])
def test_invalid_limit_fails_before_io(limit):
    from retrieval.search import retrieve
    with pytest.raises(ValueError):
        retrieve('project', ['version'], 'q', limit)
