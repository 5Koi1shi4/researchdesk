import os
import secrets
from typing import Literal
from fastapi import APIRouter, Header, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field
from generated.contracts import Citation
from generation.export import export_report

router=APIRouter()
class ExportRequest(BaseModel):
    model_config=ConfigDict(extra='forbid')
    markdown: str=Field(max_length=1000000)
    citations: list[Citation]=Field(max_length=1000)
    format: Literal['MARKDOWN','PDF']

@router.post('/internal/reports/export')
def render_export(body: ExportRequest, authorization: str=Header(default='')):
    expected=os.getenv('INTERNAL_SERVICE_TOKEN','')
    if not expected or not secrets.compare_digest('Bearer '+expected,authorization): raise HTTPException(401,'Invalid internal token')
    try: data=export_report(body.markdown,body.citations,body.format)
    except ValueError as exc: raise HTTPException(422,str(exc)) from exc
    return Response(data,media_type='application/pdf' if body.format=='PDF' else 'text/markdown; charset=utf-8')
