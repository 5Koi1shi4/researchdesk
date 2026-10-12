import sys
from pathlib import Path
sys.path.insert(0,str(Path('apps/ai').resolve()))
from generation.export import export_report
from generated.contracts import Citation
import fitz
body='# 中文研究报告\n\n中文说明：所有资源引用仅作为文本显示。\n\n| 序号 | 中文内容 |\n|---|---|\n'+'\n'.join(f'| {i} | 跨页表格验证，中文内容清晰可读。 |' for i in range(1,100))+'\n\n结论 [[cite:chunk-1]] [[cite:deleted]]'
c=[Citation(chunkId='chunk-1',documentVersionId='v1',title='长引用来源',page=1,paragraph=None,excerpt='中文长引用文本，验证换行和分页。'*250,availability='AVAILABLE'),Citation(chunkId='deleted',documentVersionId='v2',title='已删除来源',page=None,paragraph=3,excerpt='SECRET',availability='DELETED')]
p=Path('docs/export-evidence/chinese-multipage.pdf');p.write_bytes(export_report(body,c,'PDF'))
doc=fitz.open(p)
print('pages',len(doc))
for i in [0,1,len(doc)-1]:
    doc[i].get_pixmap(matrix=fitz.Matrix(1.2,1.2)).save(f'docs/export-evidence/page-{i+1}.png')
print('font',doc[0].get_fonts())
