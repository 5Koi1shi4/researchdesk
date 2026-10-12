"""Authenticated Java-only streaming endpoint; Java owns durable lifecycle and scope."""
import asyncio
import hmac
import json
import logging
import os
import threading
import time
from typing import Literal
from fastapi import APIRouter, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field
from generated.contracts import RunEvent
from .envelope import split_internal_event
from .answer import stream_answer, retrieve
from .provider import ProviderError, configured_provider, check_deadline, usage_snapshot
from .prompts import build_messages

router = APIRouter()
class HistoryMessage(BaseModel):
    model_config = ConfigDict(extra='forbid')
    role: Literal['user','assistant']
    content: str = Field(max_length=32000)
class InternalGeneration(BaseModel):
    model_config = ConfigDict(extra='forbid')
    runId: str = Field(min_length=1,max_length=100)
    kind: Literal['ANSWER','REPORT']
    projectId: str = Field(min_length=1,max_length=100)
    documentVersionIds: list[str] = Field(max_length=50)
    prompt: str = Field(min_length=1,max_length=16000)
    history: list[HistoryMessage] = Field(default_factory=list,max_length=12)
    titles: dict[str,str] = Field(default_factory=dict)

def next_item(iterator):
    try: return next(iterator)
    except StopIteration: return None

async def report_result(body, deadline, cancelled, telemetry):
    try:
        from .report import generate_report
    except ImportError:
        raise ProviderError('REPORT_NOT_AVAILABLE') from None
    retrieval_start = time.monotonic()
    evidence = await asyncio.to_thread(retrieve,body.projectId,body.documentVersionIds,body.prompt,8) if body.documentVersionIds else []
    telemetry['retrievalMs'] = int((time.monotonic()-retrieval_start)*1000)
    check_deadline(deadline)
    # Adapt the report module's async provider seam to the shared synchronous HTTP adapter.
    class AsyncProvider:
        async def stream(self, messages, max_tokens, deadline):
            provider = configured_provider()
            telemetry.update(getattr(provider,'usage',{}))
            provider_start = time.monotonic()
            first = True
            bounded_messages = build_messages(body.prompt,evidence,[])
            bounded_messages[0]['content'] += ' Write a structured Markdown research report.'
            iterator = provider.stream(bounded_messages,max_tokens=min(max_tokens,6000),deadline=deadline)
            while True:
                if cancelled.is_set(): raise ProviderError('GENERATION_CANCELLED')
                item=await asyncio.to_thread(next_item,iterator)
                if item is None: break
                if first: telemetry['ttftMs'] = int((time.monotonic()-provider_start)*1000); first = False
                yield item
            telemetry.update(getattr(provider,'usage',{}))
    result=await generate_report(evidence,AsyncProvider(),body.prompt,body.documentVersionIds,titles=body.titles,deadline=deadline)
    return result

@router.post('/internal/generation/stream')
async def generation_stream(body: InternalGeneration, authorization: str | None = Header(default=None)):
    token=os.getenv('INTERNAL_SERVICE_TOKEN','')
    if not token or not hmac.compare_digest(authorization or '', 'Bearer '+token):
        raise HTTPException(status_code=401,detail='Internal authentication required')
    deadline=time.monotonic()+(120 if body.kind=='ANSWER' else 300)
    cancelled=threading.Event()
    started=time.monotonic()
    telemetry=usage_snapshot()
    if os.getenv('GENERATION_MODE','demo') != 'demo':
        telemetry.update({'provider':'openai-compatible','mode':'REAL','model':os.getenv('OPENAI_MODEL') or None})
    async def events():
        seq=0
        def encode(kind,payload):
            nonlocal seq
            seq+=1
            event=RunEvent.model_validate({'runId':body.runId,'seq':seq,'type':kind,'payload':payload})
            packet=event.model_dump(mode='json',by_alias=True)
            if kind in ('completed','failed','cancelled'):
                telemetry['durationMs']=int((time.monotonic()-started)*1000)
                packet['usage']=dict(telemetry)
                logging.getLogger('uvicorn.error.generation').info('generation runId=%s state=%s durationMs=%s retrievalMs=%s ttftMs=%s', body.runId,kind,telemetry['durationMs'],telemetry.get('retrievalMs'),telemetry.get('ttftMs'))
            split_internal_event(packet)
            return json.dumps(packet,ensure_ascii=False)+'\n'
        yield encode('started',{'kind':body.kind})
        try:
            if body.kind=='REPORT':
                result=await asyncio.wait_for(report_result(body,deadline,cancelled,telemetry),max(.01,deadline-time.monotonic()))
                yield encode('delta',{'text':result['markdown']})
                for citation in result['citations']:
                    yield encode('citation',{'citation':citation.model_dump(mode='json',by_alias=True) if hasattr(citation,'model_dump') else citation})
                yield encode('completed',{'state':'COMPLETED'})
            else:
                iterator=stream_answer(project_id=body.projectId,allowed_version_ids=body.documentVersionIds,prompt=body.prompt,
                    history=[m.model_dump() for m in body.history],titles=body.titles,deadline=deadline,cancelled=cancelled,telemetry=telemetry)
                while True:
                    item=await asyncio.wait_for(asyncio.to_thread(next_item,iterator),max(.01,deadline-time.monotonic()))
                    if item is None: break
                    yield encode(*item)
        except asyncio.CancelledError:
            cancelled.set()
            raise
        except Exception as error:
            code=error.code if isinstance(error,ProviderError) else ('GENERATION_TIMEOUT' if isinstance(error,TimeoutError) else 'GENERATION_FAILED')
            yield encode('failed',{'error':{'code':code,'message':'生成未完成，请检查资料或稍后重试。','requestId':body.runId}})
        finally:
            cancelled.set()
    return StreamingResponse(events(),media_type='application/x-ndjson',headers={'Cache-Control':'no-store','X-Accel-Buffering':'no'})
