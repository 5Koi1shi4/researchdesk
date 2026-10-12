"""Text-only exports. No HTML, image, stylesheet or resource resolver exists."""
import io
import re
from html import escape
from pathlib import Path
from threading import Lock
from generated.contracts import Citation

_lock=Lock()

def _content(markdown, citations):
    from generation.report import MARKER
    citations=[c if isinstance(c,Citation) else Citation.model_validate(c) for c in citations]
    by_id={c.chunkId:c for c in citations}
    if len(by_id)!=len(citations): raise ValueError('Duplicate citation identifiers')
    if len(markdown)>200000: raise ValueError('Report exceeds output limit')
    ids=list(dict.fromkeys(MARKER.findall(markdown)))
    if any(i not in by_id for i in ids): raise ValueError('Unknown citation')
    if '[[cite:' in MARKER.sub('',markdown): raise ValueError('Malformed citation')
    numbers={i:n+1 for n,i in enumerate(ids)}
    content=MARKER.sub(lambda m:f'[{numbers[m[1]]}]',markdown)
    appendix=[]
    for i in ids:
        c=by_id[i]
        location=f'page {c.page}' if c.page else f'paragraph {c.paragraph}' if c.paragraph else ''
        deleted=c.availability.root=='DELETED'
        appendix.append(f'[{numbers[i]}] {c.title} ({location}) '+('DELETED — 来源已删除' if deleted else (c.excerpt or '')))
    return content+'\n\n## 引用 / Citations\n\n'+'\n\n'.join(appendix)

def export_report(markdown, citations, format):
    content=_content(markdown,citations)
    if format=='MARKDOWN': return content.encode('utf-8')
    if format!='PDF': raise ValueError('Unsupported export format')
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.lib import colors
    from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle
    with _lock:
        if 'NotoSC' not in pdfmetrics.getRegisteredFontNames():
            pdfmetrics.registerFont(TTFont('NotoSC',str(Path(__file__).parent/'fonts'/'NotoSansSC.ttf')))
    style=ParagraphStyle('Body',fontName='NotoSC',fontSize=10,leading=16,wordWrap='CJK',spaceAfter=6)
    heading=ParagraphStyle('Heading',parent=style,fontSize=15,leading=22,spaceAfter=10)
    out=io.BytesIO(); story=[]; table_rows=[]
    def flush():
        if not table_rows:return
        width=max(len(r) for r in table_rows)
        rows=[r+['']*(width-len(r)) for r in table_rows]
        table=Table([[Paragraph(escape(cell),style) for cell in row] for row in rows],colWidths=[475/width]*width,repeatRows=1,splitByRow=1,splitInRow=1)
        table.setStyle(TableStyle([('GRID',(0,0),(-1,-1),.4,colors.HexColor('#ccd4df')),('VALIGN',(0,0),(-1,-1),'TOP'),('BACKGROUND',(0,0),(-1,0),colors.HexColor('#edf1f7'))]))
        story.append(table); story.append(Spacer(1,8)); table_rows.clear()
    for line in content.splitlines():
        if line.strip().startswith('|') and line.strip().endswith('|'):
            cells=[x.strip() for x in line.strip()[1:-1].split('|')]
            if not all(re.fullmatch(r':?-+:?',x or ' ') for x in cells):table_rows.append(cells)
            continue
        flush()
        if not line.strip(): story.append(Spacer(1,6));continue
        title=re.match(r'^#{1,6}\s+(.*)',line)
        story.append(Paragraph(escape(title[1] if title else line),heading if title else style))
    flush()
    SimpleDocTemplate(out,leftMargin=60,rightMargin=60,topMargin=50,bottomMargin=50,title='ResearchDesk report').build(story)
    return out.getvalue()
