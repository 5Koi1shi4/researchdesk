"""Bounded, offline text extraction. Library stdout is never the result protocol."""
from dataclasses import dataclass
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading

MAX_BYTES = 20 * 1024 * 1024
MAX_TEXT_BYTES = 20 * 1024 * 1024
PARSER_VERSION = 'pypdf-6.12.2-markdown-v1'
_slots = threading.BoundedSemaphore(2)


@dataclass(frozen=True)
class ParsedSection:
    text: str
    page: int | None
    paragraph: int | None


class ParseError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def parse_document(content: bytes, media_type: str) -> list[ParsedSection]:
    if len(content) > min(int(os.getenv('MAX_FILE_BYTES', str(MAX_BYTES))), MAX_BYTES):
        raise ParseError('UPLOAD_TOO_LARGE')
    if not _slots.acquire(timeout=2):
        raise ParseError('PARSER_BUSY')
    try:
        with tempfile.TemporaryDirectory(prefix='researchdesk-parse-') as directory:
            source = Path(directory) / 'input'
            result = Path(directory) / 'result.json'
            source.write_bytes(content)
            try:
                process = subprocess.run([sys.executable, '-m', 'ingestion.parser_child', str(source), str(result), media_type], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10, env={'PATH': os.environ.get('PATH',''), 'SYSTEMROOT': os.environ.get('SYSTEMROOT',''), 'PYTHONPATH': str(Path(__file__).resolve().parents[1]), 'MAX_PDF_PAGES': os.environ.get('MAX_PDF_PAGES','200')})
            except subprocess.TimeoutExpired:
                raise ParseError('PARSER_RESOURCE_LIMIT') from None
            if process.returncode != 0 or not result.exists() or result.stat().st_size > 32 * 1024 * 1024:
                raise ParseError('PARSER_RESOURCE_LIMIT')
            data = json.loads(result.read_text(encoding='utf-8'))
            if 'error' in data:
                raise ParseError(data['error'])
            return [ParsedSection(**section) for section in data['sections']]
    finally:
        _slots.release()
