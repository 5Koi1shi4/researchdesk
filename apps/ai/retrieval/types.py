from dataclasses import dataclass

@dataclass(frozen=True)
class Evidence:
    chunk_id: str
    document_version_id: str
    text: str
    page: int | None
    paragraph: int | None
    score: float
