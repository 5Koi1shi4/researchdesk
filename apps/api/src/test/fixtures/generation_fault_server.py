"""Internal-only fault fixture: run explicitly on the isolated generation AI container.
Never included in the AI image and never used as a fallback provider.
"""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

pending = {}
lock = threading.Lock()
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass
    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or b'{}')
        if self.path == '/release':
            with lock:
                item = pending.get(data['runId'])
            if item:
                item.set()
            self.send_response(200); self.end_headers(); self.wfile.write(b'{}'); return
        if self.path != '/internal/generation/stream':
            self.send_error(404); return
        run_id = data['runId']
        release = threading.Event()
        with lock:
            pending[run_id] = release
        self.send_response(200)
        self.send_header('Content-Type', 'application/x-ndjson')
        self.end_headers()
        def emit(seq, kind, payload):
            self.wfile.write((json.dumps(dict(runId=run_id, seq=seq, type=kind, payload=payload))+'\n').encode())
            self.wfile.flush()
        try:
            emit(1, 'started', {'kind': data['kind']})
            emit(2, 'delta', {'text': 'Controlled draft for transaction race.'})
            release.wait(90)
            emit(3, 'completed', {'state': 'COMPLETED'})
        except (BrokenPipeError, ConnectionResetError):
            pass  # Cancellation intentionally closes the Java socket before late completion.
        finally:
            with lock:
                pending.pop(run_id, None)
    def do_GET(self):
        with lock:
            result = list(pending)
        self.send_response(200); self.end_headers(); self.wfile.write(json.dumps(result).encode())
ThreadingHTTPServer(('0.0.0.0', 8001), Handler).serve_forever()
