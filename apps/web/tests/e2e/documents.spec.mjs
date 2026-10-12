import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {mkdir} from 'node:fs/promises';
const base=process.env.ACCEPTANCE_URL||'http://localhost:18081';
assert.equal(new URL(base).port,'18081','Task 5 acceptance must use its isolated port 18081');
const output=new URL('../../../../.cache/task5-browser/',import.meta.url);
await mkdir(output,{recursive:true});
function pdf(){
 const objects=['<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 500] /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 500] /Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>','<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'];
 for(const text of ['First page source evidence','Second page source evidence']){const stream=`BT /F1 16 Tf 40 420 Td (${text}) Tj ET`;objects.push(`<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`);}
 let value='%PDF-1.4\n';const offsets=[0];objects.forEach((o,i)=>{offsets.push(Buffer.byteLength(value));value+=`${i+1} 0 obj\n${o}\nendobj\n`;});const xref=Buffer.byteLength(value);value+=`xref\n0 ${objects.length+1}\n0000000000 65535 f \n`+offsets.slice(1).map(n=>String(n).padStart(10,'0')+' 00000 n \n').join('')+`trailer\n<< /Size ${objects.length+1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;return Buffer.from(value);
}
const browser=await chromium.launch({channel:process.env.E2E_BROWSER_CHANNEL||'msedge',headless:true});
let page;
try{
 page=await browser.newPage({viewport:{width:1280,height:1000}});page.setDefaultTimeout(15000);
 await page.goto(base);await page.getByRole('button',{name:'没有账号？创建账号'}).click();
 await page.getByLabel('邮箱',{exact:true}).fill(`sources-${randomUUID()}@researchdesk.test`);await page.getByLabel('密码',{exact:true}).fill('SourceBrowserPassword9!');await page.getByRole('button',{name:'注册并进入',exact:true}).click();
 await page.getByLabel('新项目名称').fill('来源验收');await page.getByRole('button',{name:'创建项目',exact:true}).click();await page.getByRole('button',{name:/来源验收/}).click();
 async function upload(name,buffer){await page.getByLabel('资料文件').setInputFiles({name,mimeType:name.endsWith('.pdf')?'application/pdf':'text/markdown',buffer});await page.getByRole('button',{name:'上传资料',exact:true}).click();const row=page.getByRole('listitem').filter({has:page.getByRole('heading',{name,exact:true})});await row.getByText('可用于研究',{exact:true}).waitFor({timeout:60000});return row;}
 let row=await upload('source-evidence.md',Buffer.from('# Evidence title\n\n<script>window.unsafeSource=true</script>\n\nThird evidence paragraph.'));
 await page.reload();await page.getByRole('button',{name:/来源验收/}).click();row=page.getByRole('listitem').filter({has:page.getByRole('heading',{name:'source-evidence.md',exact:true})});await row.getByRole('button',{name:'预览原文'}).click();assert.equal(await page.getByRole('button',{name:'关闭原文'}).evaluate(el=>el===document.activeElement),true);await page.keyboard.press('Escape');assert.equal(await page.getByRole('region',{name:'原文预览'}).count(),0);assert.equal(await row.getByRole('button',{name:'预览原文'}).evaluate(el=>el===document.activeElement),true);await page.keyboard.press('Enter');await page.locator('.source-paragraphs p').filter({hasText:'<script>window.unsafeSource=true</script>'}).waitFor();assert.equal(await page.evaluate(()=>window.unsafeSource),undefined);
 await page.getByRole('button',{name:'定位引用段落'}).focus();await page.keyboard.press('Enter');assert.equal(await page.locator('.source-paragraphs [aria-current]').evaluate(el=>el===document.activeElement),true);
 await page.screenshot({path:new URL('markdown.png',output).pathname.replace(/^\/(.:)/,'$1'),fullPage:true});
 await page.keyboard.press('Escape');assert.equal(await page.getByRole('region',{name:'原文预览'}).count(),0);
 await row.getByRole('button',{name:'删除资料'}).click();await page.getByRole('button',{name:'取消删除'}).click();await row.getByRole('button',{name:'预览原文'}).click();await page.locator('.source-paragraphs p').filter({hasText:'Third evidence paragraph.'}).waitFor();
 await row.getByRole('button',{name:'删除资料'}).click();await page.getByRole('button',{name:'确认删除'}).click();await page.getByText('资料已撤销访问。历史回答正文仍会保留。',{exact:true}).waitFor();assert.equal(await page.locator('.source-paragraphs p').filter({hasText:'Third evidence paragraph.'}).count(),0);
 row=await upload('source-pages.pdf',pdf());await row.getByRole('button',{name:'预览原文'}).click();
 const first=page.getByRole('img',{name:'PDF 原文第 1 页'});await first.waitFor();await first.evaluate(img=>img.decode());assert.ok(await first.evaluate(img=>img.naturalWidth)>100);
 await page.getByRole('button',{name:'下一页'}).click();const second=page.getByRole('img',{name:'PDF 原文第 2 页'});await second.evaluate(img=>img.decode());assert.ok(await second.evaluate(img=>img.naturalHeight)>100);await page.screenshot({path:new URL('pdf-page-2.png',output).pathname.replace(/^\/(.:)/,'$1'),fullPage:true});
 await page.setViewportSize({width:390,height:844});await page.screenshot({path:new URL('mobile.png',output).pathname.replace(/^\/(.:)/,'$1'),fullPage:true});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth),false);
 await row.getByRole('button',{name:'删除资料'}).click();await page.getByRole('button',{name:'确认删除'}).click();await page.getByText('资料已撤销访问。历史回答正文仍会保留。',{exact:true}).waitFor();
 console.log('PASS Task5 real browser: upload/reload/READY/safe Markdown/keyboard focus/Escape/cancel/delete/PDF page pixels/page navigation/mobile');
}catch(error){if(page){await page.screenshot({path:new URL('failure.png',output).pathname.replace(/^\/(.:)/,'$1'),fullPage:true});console.error('Source preview errors:',await page.getByRole('alert').allTextContents());}throw error;}finally{await browser.close();}
