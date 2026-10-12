import importlib.util
import io
import pytest


def parser():
    assert importlib.util.find_spec('ingestion') is not None, 'bounded ingestion parser is missing'
    from ingestion.parser import parse_document, ParseError
    return parse_document, ParseError


def test_markdown_retains_heading_and_paragraph_numbers():
    parse, _ = parser()
    sections = parse(b'# Evidence\n\nFirst paragraph.\n\nSecond paragraph.', 'text/markdown')
    assert [(s.text, s.page, s.paragraph) for s in sections] == [('# Evidence', None, 1), ('First paragraph.', None, 2), ('Second paragraph.', None, 3)]


@pytest.mark.parametrize('content,media,code', [(b'\xc3(', 'text/markdown', 'INVALID_UTF8'), (b' \n', 'text/markdown', 'DOCUMENT_NO_TEXT'), (b'%PDF-1.7\nbroken', 'application/pdf', 'INVALID_PDF')])
def test_permanent_parse_errors_are_distinct(content, media, code):
    parse, error = parser()
    with pytest.raises(error) as failure:
        parse(content, media)
    assert failure.value.code == code


def test_pdf_retains_page_and_rejects_encryption_no_text_and_page_limit():
    parse, error = parser()
    from pypdf import PdfWriter, PdfReader
    from pypdf.generic import DecodedStreamObject, NameObject, DictionaryObject
    writer = PdfWriter()
    page = writer.add_blank_page(612, 792)
    stream = DecodedStreamObject(); stream.set_data(b'BT /F1 12 Tf 50 700 Td (Evidence page one) Tj ET')
    font = DictionaryObject({NameObject('/Type'): NameObject('/Font'), NameObject('/Subtype'): NameObject('/Type1'), NameObject('/BaseFont'): NameObject('/Helvetica')})
    page[NameObject('/Resources')] = DictionaryObject({NameObject('/Font'): DictionaryObject({NameObject('/F1'): writer._add_object(font)})})
    page[NameObject('/Contents')] = writer._add_object(stream)
    data = io.BytesIO(); writer.write(data)
    assert [(s.text, s.page) for s in parse(data.getvalue(), 'application/pdf')] == [('Evidence page one', 1)]
    for count, encrypted, code in [(1, False, 'DOCUMENT_NO_TEXT'), (201, False, 'PDF_PAGE_LIMIT'), (1, True, 'ENCRYPTED_PDF')]:
        writer = PdfWriter()
        for _ in range(count): writer.add_blank_page(612, 792)
        if encrypted: writer.encrypt('secret')
        data = io.BytesIO(); writer.write(data)
        with pytest.raises(error) as failure: parse(data.getvalue(), 'application/pdf')
        assert failure.value.code == code


def test_pdf_decoded_stream_limit_is_enforced_in_child():
    parse, error = parser()
    from pypdf import PdfWriter
    from pypdf.generic import DecodedStreamObject, NameObject
    writer=PdfWriter();page=writer.add_blank_page(612,792)
    stream=DecodedStreamObject();stream.set_data(b' '*(33*1024*1024))
    page[NameObject('/Contents')]=writer._add_object(stream.flate_encode())
    data=io.BytesIO();writer.write(data)
    with pytest.raises(error) as failure: parse(data.getvalue(),'application/pdf')
    assert failure.value.code == 'PARSER_RESOURCE_LIMIT'
