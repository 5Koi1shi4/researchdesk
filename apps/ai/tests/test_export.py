import pytest
from generated.contracts import Citation
from generation.export import export_report

def citation(deleted=False):
    return Citation(chunkId='c1',documentVersionId='v1',title='中文来源',page=1,paragraph=None,excerpt='PRIVATE' if deleted else '依据'*100,availability='DELETED' if deleted else 'AVAILABLE')

def test_markdown_appendix_deleted_excerpt_removed():
    data=export_report('事实 [[cite:c1]]',[citation(True)],'MARKDOWN').decode()
    assert 'DELETED' in data and 'PRIVATE' not in data

def test_unknown_citation_rejected():
    with pytest.raises(ValueError): export_report('[[cite:fake]]',[],'PDF')

def test_pdf_chinese_multipage_and_resources_are_text(monkeypatch):
    import socket
    monkeypatch.setattr(socket,'create_connection',lambda *a,**k: (_ for _ in ()).throw(AssertionError('network')))
    text='# 中文报告\n\n![external](https://example.invalid/a.png)\n<img src="file:///secret">\n'+('\n| 中文列 | 描述 |\n|---|---|\n| 内容 | 多页内容测试 |\n'*80)+'\n[[cite:c1]]'
    data=export_report(text,[citation()],'PDF')
    from pypdf import PdfReader
    import io
    pdf=PdfReader(io.BytesIO(data))
    assert len(pdf.pages)>2
    assert '中文报告' in ''.join(p.extract_text() for p in pdf.pages)
    assert any('/FontFile2' in f.get_object().get('/FontDescriptor',{}).get_object() for p in pdf.pages for f in p['/Resources']['/Font'].values() if '/FontDescriptor' in f.get_object())

def test_internal_auth(monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from generation.export_routes import router
    monkeypatch.setenv('INTERNAL_SERVICE_TOKEN','x'*32)
    app=FastAPI(); app.include_router(router); client=TestClient(app)
    body={'markdown':'中文','citations':[],'format':'MARKDOWN'}
    assert client.post('/internal/reports/export',json=body).status_code==401
    assert client.post('/internal/reports/export',json=body,headers={'Authorization':'Bearer '+'x'*32}).status_code==200

def test_oversized_table_cell_splits():
    data=export_report('| header |\n|---|\n| '+'中文'*4000+' |',[],'PDF')
    assert data.startswith(b'%PDF')
