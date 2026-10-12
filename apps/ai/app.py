import os

import psycopg
import redis
from fastapi import FastAPI
from fastapi.responses import JSONResponse

app = FastAPI(title="ResearchDesk internal AI service")


@app.get("/health")
def health():
    return {"status": "UP"}


@app.get("/ready")
def ready():
    try:
        with psycopg.connect(
            os.getenv("DATABASE_URL", "postgresql://researchdesk:researchdesk-dev-only@localhost:15432/researchdesk"),
            connect_timeout=2,
        ) as connection:
            connection.execute("SELECT 1").fetchone()
        with redis.Redis.from_url(
            os.getenv("REDIS_URL", "redis://localhost:16379/0"),
            socket_connect_timeout=2,
            socket_timeout=2,
        ) as queue:
            queue.ping()
    except (psycopg.Error, redis.RedisError):
        return JSONResponse({"status": "DOWN"}, status_code=503)
    return {"status": "UP"}
