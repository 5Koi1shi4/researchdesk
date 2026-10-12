import {chromium,request} from 'playwright';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../../..');
const project=process.env.COMPOSE_PROJECT_NAME||'researchdesk-test-integration';
const base=process.env.ACCEPTANCE_URL||'http://127.0.0.1:18087';
const target=new URL(base);
assert.match(project,/^researchdesk-test-[a-z0-9-]+$/,'Require isolated test project');
assert.ok(['localhost','127.0.0.1'].includes(target.hostname)&&target.protocol==='http:'&&target.pathname==='/'&&!target.search&&!target.hash&&!target.username,'Require loopback test origin');
assert.ok(Number(target.port)>=18087&&Number(target.port)<=18999,'Refuse baseline and module ports');
const binding=spawnSync('docker',['compose','-p',project,'-f',path.join(root,'infra/compose.yaml'),'port','proxy','8080'],{encoding:'utf8',timeout:15000});
assert.equal(binding.status,0,binding.stderr);assert.equal(binding.stdout.trim(),'127.0.0.1:'+target.port,'Port must match selected isolated project');
const output=path.join(root,'.cache/integration-browser');await mkdir(output,{recursive:true});
const context=await chromium.launchPersistentContext(path.join(output,'profile-'+randomUUID()),{channel:'msedge',headless:true,viewport:{width:1360,height:1000},timeout:30000});
const page=await context.newPage();page.setDefaultTimeout(20000);
const evidence={project,base,startedAt:new Date().toISOString(),checks:[]};
const check=name=>{evidence.checks.push(name);console.log('PASS '+name);};
const read=async url=>{const response=await page.request.get(base+url);assert.equal(response.status(),200,await response.text());return response.json();};
try{
 await page.goto(base);await page.getByRole('button',{name:'没有账号？创建账号'}).click();
 await page.getByLabel('邮箱',{exact:true}).fill('journey-'+randomUUID()+'@researchdesk.test');await page.getByLabel('密码',{exact:true}).fill('JourneyPassword9!');await page.getByRole('button',{name:'注册并进入',exact:true}).click();
 const name='端到端研究 '+randomUUID().slice(0,8);await page.getByLabel('新项目名称').fill(name);await page.getByRole('button',{name:'创建项目',exact:true}).click();await page.getByRole('button',{name:new RegExp(name)}).click();
 const source='# 数据库索引研究\n\n数据库索引能减少查询扫描行数，提高检索速度。索引也会增加写入和存储成本。\n\n选择索引应依据查询条件、数据分布与写入频率，通过执行计划核实收益。';
 await page.getByLabel('资料文件',{exact:true}).setInputFiles({name:'journey-source.md',mimeType:'text/markdown',buffer:Buffer.from(source)});await page.getByRole('button',{name:'上传资料',exact:true}).click();await page.getByText('可用于研究',{exact:true}).waitFor({timeout:90000});
 const session=await read('/api/auth/session');const projects=await read('/api/projects');const projectId=projects.find(p=>p.name===name).id;const docs=await read('/api/projects/'+projectId+'/documents');assert.equal(docs.length,1);assert.equal(docs[0].state,'READY');const doc=docs[0];check('real upload → ingestion READY');
 await page.getByRole('button',{name:'研究问答',exact:true}).click();await page.getByLabel('journey-source.md',{exact:true}).check();await page.getByLabel('研究问题',{exact:true}).fill('索引的收益与成本是什么？');await page.getByRole('button',{name:'开始研究',exact:true}).click();await page.getByText('生成完成',{exact:true}).waitFor({timeout:90000});
 const answerId=new URL(page.url()).searchParams.get('run');const answer=await read('/api/runs/'+answerId);assert.equal(answer.state,'COMPLETED');assert.equal(answer.mode,'DEMO');assert.deepEqual(answer.documentVersionIds,[doc.versionId]);assert.ok(answer.citations.length);assert.ok(answer.citations.every(c=>c.documentVersionId===doc.versionId));await page.getByText('演示模式 · 非真实模型',{exact:true}).waitFor();
 await page.getByRole('button',{name:/^引用 1：/}).click();await page.getByRole('region',{name:'原文预览'}).locator('.source-paragraphs').getByText('选择索引应依据查询条件、数据分布与写入频率，通过执行计划核实收益。').waitFor();check('scoped ANSWER + demo label + full authorized source');
 await page.getByLabel('研究问题',{exact:true}).fill('总结索引的收益、成本与选型建议');await page.getByRole('button',{name:'生成报告',exact:true}).click();await page.getByRole('button',{name:'打开生成的报告',exact:true}).waitFor({timeout:90000});
 const runId=new URL(page.url()).searchParams.get('run');assert.notEqual(runId,answerId);const run=await read('/api/runs/'+runId);assert.equal(run.kind,'REPORT');assert.equal(run.state,'COMPLETED');const generated=await read('/api/runs/'+runId+'/report');evidence.runId=runId;evidence.reportId=generated.id;
 await page.getByRole('button',{name:'打开生成的报告',exact:true}).click();const editor=page.getByLabel('报告 Markdown');await editor.waitFor();await page.getByText('演示模型生成 · 尚未验证真实模型质量',{exact:true}).waitFor();const original=await editor.inputValue();assert.ok(original.includes('[[cite:'));check('real REPORT pipeline creates exact editable draft');
 await editor.fill(original+'\n\n## 人工复核\n已补充人工复核意见。');await page.getByRole('button',{name:'保存新版本',exact:true}).click();await page.getByText('已保存版本 2',{exact:true}).waitFor();
 const v1=await read('/api/reports/'+generated.id+'/versions/1');assert.equal(v1.markdown,original);const v2=await read('/api/reports/'+generated.id+'/versions/2');assert.ok(v2.markdown.includes('人工复核'));assert.ok(v2.citations.length);await page.getByRole('button',{name:/^引用 1：/}).first().click();await page.getByRole('region',{name:'原文预览'}).waitFor();
 await page.getByLabel('查看版本').selectOption('1');await page.getByText('历史版本只读',{exact:true}).waitFor();assert.equal(await editor.count(),0);await page.getByLabel('查看版本').selectOption('current');check('save immutable version + history + report source viewer');
 const downloads={};for(const format of ['Markdown','PDF']){
  await page.getByRole('button',{name:'导出 '+format,exact:true}).click();const link=page.getByRole('link',{name:`下载 ${format}（版本 2）`});await link.waitFor({timeout:90000});downloads[format]=await link.getAttribute('href');const pending=page.waitForEvent('download');await link.click();const download=await pending;const file=path.join(output,format==='PDF'?'report-v2.pdf':'report-v2.md');await download.saveAs(file);const bytes=await readFile(file);if(format==='PDF')assert.equal(bytes.subarray(0,5).toString(),'%PDF-');else {assert.ok(bytes.toString().includes('人工复核'));assert.ok(bytes.toString().includes('journey-source.md'));}
 }check('actual Markdown and PDF downloads with content/signature');
 await page.screenshot({path:path.join(output,'journey-desktop.png'),fullPage:true});await page.setViewportSize({width:390,height:844});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),'mobile report overflow');await page.screenshot({path:path.join(output,'journey-mobile.png'),fullPage:true});await page.setViewportSize({width:1360,height:1000});check('mobile390 editor layout');
 const stranger=await request.newContext({baseURL:base});try{const registered=await stranger.post('/api/auth/register',{data:{email:'other-'+randomUUID()+'@researchdesk.test',password:'JourneyPassword9!'}});assert.equal(registered.status(),201);for(const url of ['/api/projects/'+projectId,'/api/runs/'+runId,'/api/runs/'+runId+'/report','/api/reports/'+generated.id,'/api/document-versions/'+doc.versionId+'/source-info','/api/projects/'+projectId+'/usage'])assert.equal((await stranger.get(url)).status(),404,'owner isolation '+url);}finally{await stranger.dispose();}check('foreign account isolation including run-report lookup');
 await page.getByRole('button',{name:'项目资料',exact:true}).click();await page.getByRole('button',{name:'删除资料',exact:true}).click();await page.getByRole('button',{name:'确认删除',exact:true}).click();await page.getByText('资料已撤销访问。历史回答正文仍会保留。',{exact:true}).waitFor();
 const headers={'X-CSRF-Token':session.csrfToken};const retry=await page.request.post(base+'/api/projects/'+projectId+'/tasks/'+doc.taskId+'/retry',{headers,data:{}});assert.equal(retry.status(),409,'deleted source cannot retry');
 const redacted=await read('/api/reports/'+generated.id+'/versions/2');assert.ok(redacted.citations.every(c=>c.availability==='DELETED'&&c.excerpt===null));const redactedRun=await read('/api/runs/'+answerId);assert.ok(redactedRun.citations.every(c=>c.availability==='DELETED'&&c.excerpt===null));assert.equal((await page.request.get(base+downloads.PDF)).status(),409,'stale export denied after source deletion');
 await page.getByRole('button',{name:'报告',exact:true}).click();await page.getByRole('button',{name:new RegExp('总结索引.*版本 2')}).click();await page.getByRole('button',{name:/^引用 1：/}).first().click();await page.getByText('来源已删除，原文与摘录无法访问。',{exact:true}).waitFor();assert.equal(await page.getByRole('region',{name:'原文预览'}).count(),0);check('deletion redacts answer/report citations, denies old attachment and retry409');
 await writeFile(path.join(output,'result.json'),JSON.stringify({...evidence,completedAt:new Date().toISOString()},null,2));console.log('Evidence '+output);
}catch(error){await page.screenshot({path:path.join(output,'journey-failure.png'),fullPage:true}).catch(()=>{});throw error;}finally{await context.close();}

