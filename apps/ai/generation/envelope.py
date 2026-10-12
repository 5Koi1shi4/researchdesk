"""Internal NDJSON envelope; usage is never part of the browser RunEvent contract."""
from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field
from generated.contracts import RunEvent

Count = Annotated[int, Field(strict=True, ge=0, le=9007199254740991)]
class InternalUsage(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)
    provider: Literal['demo', 'openai-compatible']
    model: Annotated[str, Field(max_length=200)] | None
    mode: Literal['DEMO', 'REAL']
    inputTokens: Count | None
    outputTokens: Count | None
    totalTokens: Count | None
    durationMs: Count | None = None
    retrievalMs: Count | None = None
    ttftMs: Count | None = None


def split_internal_event(packet):
    """Validate the private extension, then independently validate the public event."""
    if not isinstance(packet, dict):
        raise ValueError('Invalid internal event')
    public = dict(packet)
    present = 'usage' in public
    raw = public.pop('usage', None)
    if present and public.get('type') not in ('completed', 'failed', 'cancelled'):
        raise ValueError('Usage is only valid on terminal events')
    usage = InternalUsage.model_validate(raw) if present else None
    return RunEvent.model_validate(public), usage
