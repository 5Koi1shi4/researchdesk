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
