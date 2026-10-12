"""One parse per process; constrain memory, CPU, decoded streams, files and network."""
import ctypes
from dataclasses import asdict
import io
import json
import os
from pathlib import Path
import re
import sys


def memory_limit():
    limit = 256 * 1024 * 1024
    if os.name == 'posix':
        import resource
        resource.setrlimit(resource.RLIMIT_AS, (limit, limit))
        resource.setrlimit(resource.RLIMIT_CPU, (8, 8))
        resource.setrlimit(resource.RLIMIT_FSIZE, (32 * 1024 * 1024, 32 * 1024 * 1024))
    elif os.name == 'nt':
        # A child-owned job limits this process even on host Windows tests.
        from ctypes import wintypes
        class BASIC(ctypes.Structure):
            _fields_=[('PerProcessUserTimeLimit',ctypes.c_longlong),('PerJobUserTimeLimit',ctypes.c_longlong),('LimitFlags',wintypes.DWORD),('MinimumWorkingSetSize',ctypes.c_size_t),('MaximumWorkingSetSize',ctypes.c_size_t),('ActiveProcessLimit',wintypes.DWORD),('Affinity',ctypes.c_size_t),('PriorityClass',wintypes.DWORD),('SchedulingClass',wintypes.DWORD)]
        class IO(ctypes.Structure):
            _fields_=[(name,ctypes.c_ulonglong) for name in ('ReadOperationCount','WriteOperationCount','OtherOperationCount','ReadTransferCount','WriteTransferCount','OtherTransferCount')]
        class EXTENDED(ctypes.Structure):
            _fields_=[('BasicLimitInformation',BASIC),('IoInfo',IO),('ProcessMemoryLimit',ctypes.c_size_t),('JobMemoryLimit',ctypes.c_size_t),('PeakProcessMemoryUsed',ctypes.c_size_t),('PeakJobMemoryUsed',ctypes.c_size_t)]
        kernel=ctypes.WinDLL('kernel32',use_last_error=True)
        kernel.CreateJobObjectW.restype=wintypes.HANDLE
        kernel.SetInformationJobObject.argtypes=[wintypes.HANDLE,ctypes.c_int,ctypes.c_void_p,wintypes.DWORD]
        kernel.AssignProcessToJobObject.argtypes=[wintypes.HANDLE,wintypes.HANDLE]
        kernel.GetCurrentProcess.restype=wintypes.HANDLE
        job=kernel.CreateJobObjectW(None,None); info=EXTENDED();info.BasicLimitInformation.LimitFlags=0x100;info.ProcessMemoryLimit=limit
        if not job or not kernel.SetInformationJobObject(job,9,ctypes.byref(info),ctypes.sizeof(info)) or not kernel.AssignProcessToJobObject(job,kernel.GetCurrentProcess()):
            raise RuntimeError('Cannot establish parser memory boundary')
    else:
        raise RuntimeError('Unsupported parser memory boundary')


def main():
    memory_limit()
    from ingestion.parser import ParsedSection, ParseError, MAX_TEXT_BYTES
    from pypdf import PdfReader
    from pypdf.generic import EncodedStreamObject, DecodedStreamObject
    # PDF strings select built-in codecs lazily; load trusted interpreter modules before denying files.
    import encodings
    import importlib
    import pkgutil
    for codec in pkgutil.iter_modules(encodings.__path__):
        try: importlib.import_module('encodings.'+codec.name)
        except (ImportError, LookupError): pass
    source, destination, media = sys.argv[1:]
    content = Path(source).read_bytes()
    output = open(destination, 'w', encoding='utf-8')
    # Import and protocol file open precede the sandbox. No document-controlled path can be read.
    def audit(event, arguments):
        if event == 'open' or event.startswith(('socket.', 'subprocess.', 'os.system', 'ctypes.dlopen')):
            raise PermissionError('External resources are disabled during parsing')
    sys.addaudithook(audit)
    decoded = 0
    for stream_type in (EncodedStreamObject, DecodedStreamObject):
        original = stream_type.get_data
        def bounded(self, _original=original):
            nonlocal decoded
            data = _original(self); decoded += len(data)
            if decoded > 32 * 1024 * 1024: raise ParseError('PARSER_RESOURCE_LIMIT')
            return data
        stream_type.get_data = bounded
    try:
        sections=[]
        if media == 'text/markdown':
            try: text=content.decode('utf-8',errors='strict')
            except UnicodeDecodeError: raise ParseError('INVALID_UTF8') from None
            sections=[ParsedSection(part.strip(),None,number) for number,part in enumerate(re.split(r'\n\s*\n',text.replace('\r\n','\n')),1) if part.strip()]
        elif media == 'application/pdf':
            try:
                reader=PdfReader(io.BytesIO(content),strict=True)
                if reader.is_encrypted: raise ParseError('ENCRYPTED_PDF')
                if len(reader.pages)>min(int(os.getenv('MAX_PDF_PAGES','200')),200): raise ParseError('PDF_PAGE_LIMIT')
                total=0
                for number,page in enumerate(reader.pages,1):
                    text=page.extract_text().strip();total+=len(text.encode('utf-8'))
                    if total>MAX_TEXT_BYTES: raise ParseError('PARSER_RESOURCE_LIMIT')
                    if text: sections.append(ParsedSection(text,number,None))
            except ParseError: raise
            except MemoryError: raise ParseError('PARSER_RESOURCE_LIMIT') from None
            except Exception as error:
                print('PDF parser failure: '+type(error).__name__,file=sys.stderr)
                raise ParseError('INVALID_PDF') from None
        else: raise ParseError('INVALID_DOCUMENT_TYPE')
        if not sections: raise ParseError('DOCUMENT_NO_TEXT')
        json.dump({'sections':[asdict(section) for section in sections]},output,ensure_ascii=False)
    except ParseError as error:
        json.dump({'error':error.code},output)
    finally: output.close()


if __name__ == '__main__': main()
