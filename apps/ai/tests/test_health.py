import os
import asyncio

from httpx import AsyncClient, ASGITransport
from app import app

def get(path):
    async def request():
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            return await client.get(path)
    return asyncio.run(request())


def test_liveness_does_not_depend_on_database_or_queue():
    response = get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_readiness_probes_real_database_and_queue():
    expected = int(os.getenv("EXPECTED_READINESS", "503"))
    assert get("/ready").status_code == expected


def test_readiness_bounds_database_statement(monkeypatch):
    # Probe the connection contract without requiring a hanging external database.
    import app as module
    import psycopg
    captured = {}
    def unavailable(*args, **kwargs):
        captured.update(kwargs)
        raise psycopg.OperationalError("unavailable")
    monkeypatch.setattr(module.psycopg, "connect", unavailable)
    assert get("/ready").status_code == 503
    assert captured["connect_timeout"] <= 2
    assert captured.get("options") == "-c statement_timeout=2000"


def test_readiness_handles_real_redis_connection_failure_after_database_success(monkeypatch):
    # Keep the Redis client/connection constructor real so invalid kwargs cannot hide.
    from contextlib import nullcontext
    from types import SimpleNamespace
    import app as module
    connection=SimpleNamespace(execute=lambda query: SimpleNamespace(fetchone=lambda: (1,)))
    monkeypatch.setattr(module.psycopg, 'connect', lambda *a, **kw: nullcontext(connection))
    monkeypatch.setenv('REDIS_URL', 'redis://127.0.0.1:1/0')
    assert get('/ready').status_code == 503
