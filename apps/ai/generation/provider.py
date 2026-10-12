"""Server-only providers. Demo is explicit; real configuration never falls back."""
import json
import os
import socket
import threading
import time
import http.client
import urllib.parse

class ProviderError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)

def check_deadline(deadline):
    if time.monotonic() >= deadline:
        raise ProviderError('GENERATION_TIMEOUT')

class DemoProvider:
    mode = 'demo'
    def stream(self, messages, max_tokens, deadline):
        check_deadline(deadline)
        evidence = json.loads(messages[-1]['content'])['evidence']
        text = '【演示模式：确定性资料摘录，未经真实模型生成】\n' + '\n'.join(
            f"{item['text'][:300]} [[cite:{item['chunk_id']}]]" for item in evidence[:3])
        try:
            delay = min(1000, max(0, int(os.getenv('DEMO_CHUNK_DELAY_MS','0'))))/1000
        except ValueError:
            delay = 0
        for start in range(0, len(text), 64):
            if delay: time.sleep(min(delay, max(0, deadline-time.monotonic())))
            check_deadline(deadline)
            yield text[start:start+64]

class OpenAICompatibleProvider:
    mode = 'openai-compatible'
    def __init__(self, base_url, model, api_key):
        parsed = urllib.parse.urlsplit(base_url)
        if parsed.scheme not in ('http','https') or not parsed.netloc or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ProviderError('PROVIDER_NOT_CONFIGURED')
        if not model or not api_key:
            raise ProviderError('PROVIDER_NOT_CONFIGURED')
        self.url, self.model, self.api_key = base_url.rstrip('/')+'/chat/completions', model, api_key

    def stream(self, messages, max_tokens, deadline):
        check_deadline(deadline)
        body = json.dumps({'model':self.model,'messages':messages,'max_tokens':max_tokens,'stream':True}).encode()
        parsed = urllib.parse.urlsplit(self.url)
        connection_type = http.client.HTTPSConnection if parsed.scheme == 'https' else http.client.HTTPConnection
        connection = connection_type(parsed.hostname, parsed.port, timeout=max(.01,min(5,deadline-time.monotonic())))
        expired = threading.Event()
        deadline_timer = None
        # Retries are deliberately not transparent after any stream bytes; Java owns retry UX.
        try:
            connection.connect()
            stream_socket = connection.sock
            def expire_socket():
                expired.set()
                try: stream_socket.shutdown(socket.SHUT_RDWR)
                except OSError: pass
            deadline_timer = threading.Timer(max(.001,deadline-time.monotonic()),expire_socket)
            deadline_timer.daemon = True
            deadline_timer.start()
            stream_socket.settimeout(max(.01,deadline-time.monotonic()))
            connection.request('POST',parsed.path or '/',body=body,headers={'Content-Type':'application/json','Authorization':'Bearer '+self.api_key})
            with connection.getresponse() as response:
                if expired.is_set(): raise ProviderError('GENERATION_TIMEOUT')
                if 300 <= response.status < 400: raise ProviderError('PROVIDER_REDIRECT_REJECTED')
                if response.status != 200: raise ProviderError('PROVIDER_UNAVAILABLE')
                if 'text/event-stream' not in response.getheader('Content-Type',''):
                    raise ProviderError('PROVIDER_PROTOCOL_ERROR')
                text_seen = False
                finished = False
                while True:
                    check_deadline(deadline)
                    stream_socket.settimeout(max(.01,deadline-time.monotonic()))
                    line = response.readline(65537)
                    if expired.is_set(): raise ProviderError('GENERATION_TIMEOUT')
                    check_deadline(deadline)
                    if len(line)>65536: raise ProviderError('PROVIDER_PROTOCOL_ERROR')
                    if not line: break
                    if not line.startswith(b'data:'): continue
                    data = line[5:].strip()
                    if data == b'[DONE]':
                        if not text_seen or not finished: raise ProviderError('PROVIDER_PROTOCOL_ERROR')
                        return
                    try:
                        packet = json.loads(data)
                        if 'error' in packet: raise ProviderError('PROVIDER_UNAVAILABLE')
                        choices = packet.get('choices')
                        if choices == [] and 'usage' in packet: continue
                        choice = choices[0]
                        reason = choice.get('finish_reason')
                        if reason is not None:
                            if reason != 'stop': raise ProviderError('PROVIDER_OUTPUT_INCOMPLETE')
                            finished = True
                        content = choice.get('delta',{}).get('content')
                        if content is not None and not isinstance(content,str): raise ValueError()
                    except (ValueError, TypeError, KeyError, IndexError, AttributeError):
                        raise ProviderError('PROVIDER_PROTOCOL_ERROR') from None
                    if content:
                        text_seen = True
                        yield content
                raise ProviderError('PROVIDER_OUTPUT_INCOMPLETE')
        except (socket.timeout, TimeoutError):
            raise ProviderError('GENERATION_TIMEOUT') from None
        except (http.client.HTTPException, OSError):
            if expired.is_set(): raise ProviderError('GENERATION_TIMEOUT') from None
            raise ProviderError('PROVIDER_UNAVAILABLE') from None
        finally:
            if deadline_timer is not None: deadline_timer.cancel()
            connection.close()

def configured_provider():
    mode = os.getenv('GENERATION_MODE','demo')
    if mode == 'demo': return DemoProvider()
    if mode != 'openai-compatible': raise ProviderError('PROVIDER_NOT_CONFIGURED')
    return OpenAICompatibleProvider(os.getenv('OPENAI_BASE_URL',''), os.getenv('OPENAI_MODEL',''), os.getenv('OPENAI_API_KEY',''))
