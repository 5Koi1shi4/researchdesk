import os
import threading
from contextlib import asynccontextmanager

import psycopg
import redis
from fastapi import FastAPI
from fastapi.responses import JSONResponse

@asynccontextmanager
async def lifespan(app):
    from worker.consumer import consume
    stop=threading.Event()
    thread=threading.Thread(target=consume,args=(stop,),name='ingestion-consumer',daemon=True)
    thread.start()
    yield
    stop.set();thread.join(timeout=6)

app = FastAPI(title="ResearchDesk internal AI service",lifespan=lifespan)


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

from generation.export_routes import router as export_router
app.include_router(export_router)
