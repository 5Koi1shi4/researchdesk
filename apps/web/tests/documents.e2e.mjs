import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {mkdir} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../../..');
const compose=path.join(root,'infra/compose.yaml');
const output=path.join(root,'.cache/task3-browser/screenshots');
await mkdir(output,{recursive:true});
const email='documents-e2e-'+randomUUID()+'@researchdesk.test';
const projectName='资料浏览器验收 '+randomUUID().slice(0,8);
function sql(query) {
 const result=spawnSync('docker',['compose','-f',compose,'exec','-T','postgres','psql','-U','researchdesk','-d','researchdesk','-v','ON_ERROR_STOP=1','-At','-c',query],{encoding:'utf8',timeout:15000});
 assert.equal(result.status,0,result.stderr);return result.stdout.trim();
}
const browser=await chromium.launch({channel:process.env.E2E_BROWSER_CHANNEL||'msedge',headless:true,timeout:15000});
let projectId;
try {
 const page=await browser.newPage({viewport:{width:1360,height:1000}});
 page.setDefaultTimeout(10000);
 await page.goto(process.env.ACCEPTANCE_URL||'http://localhost:18080');
 await page.getByRole('button',{name:'没有账号？创建账号'}).click();
 await page.getByLabel('邮箱',{exact:true}).fill(email);
 await page.getByLabel('密码',{exact:true}).fill('BrowserFixturePassword9!');
 await page.getByRole('button',{name:'注册并进入',exact:true}).click();
 await page.getByRole('heading',{name:'我的项目',exact:true}).waitFor();
 await page.getByLabel('新项目名称').fill(projectName);
 await page.getByRole('button',{name:'创建项目',exact:true}).click();
 await page.getByRole('button',{name:new RegExp(projectName)}).click();
 await page.getByRole('heading',{name:'项目资料',exact:true}).waitFor();
 await page.getByRole('heading',{name:'上传第一份资料',exact:true}).waitFor();
 assert.equal(await page.getByText(/配置的模型服务/).count(),1);
 assert.equal(await page.getByRole('button',{name:'上传资料',exact:true}).isDisabled(),true);
 await page.getByLabel('资料文件',{exact:true}).setInputFiles({name:'browser-source.md',mimeType:'text/markdown',buffer:Buffer.from('# Browser fixture\n\nAn index can reduce lookups.\n')});
 await page.getByRole('button',{name:'上传资料',exact:true}).click();
 await page.getByRole('heading',{name:'browser-source.md',exact:true}).waitFor();
 assert.equal(await page.getByText('处理中',{exact:true}).count(),1);
 assert.equal(await page.getByText('可用于研究',{exact:true}).count(),0);
 projectId=sql("SELECT p.id FROM business.projects p JOIN business.users u ON u.id=p.owner_id WHERE u.email='"+email+"'");
 assert.match(projectId,/^[a-f0-9-]{36}$/);
 assert.equal(sql("SELECT state FROM business.tasks WHERE project_id='"+projectId+"'"),'QUEUED');
 assert.equal(sql("SELECT count(*) FROM business.outbox o JOIN business.tasks t ON t.id=o.task_id WHERE t.project_id='"+projectId+"'"),'1');
 await page.screenshot({path:path.join(output,'documents-desktop.png'),fullPage:true});
 await page.getByLabel('资料文件',{exact:true}).setInputFiles({name:'invalid.pdf',mimeType:'application/pdf',buffer:Buffer.from('# This is Markdown disguised as PDF')});
 await page.getByRole('button',{name:'上传资料',exact:true}).click();
 await page.getByRole('alert').filter({hasText:'文件内容与扩展名不匹配'}).waitFor();
 assert.equal(await page.getByRole('heading',{name:'invalid.pdf',exact:true}).count(),0);
 assert.equal(await page.getByRole('button',{name:'上传资料',exact:true}).isEnabled(),true);
 sql("UPDATE business.documents SET state='FAILED',error_code='PARSE_FAILED',error_message='验收资料无法解析，请检查原文件后重新上传。' WHERE project_id='"+projectId+"'");
 await page.getByRole('button',{name:'刷新资料状态',exact:true}).click();
 await page.getByText('处理失败',{exact:true}).waitFor();
 await page.getByText('验收资料无法解析，请检查原文件后重新上传。',{exact:true}).waitFor();
 await page.setViewportSize({width:390,height:844});
 assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),'Mobile must not overflow horizontally');
 await page.screenshot({path:path.join(output,'documents-mobile.png'),fullPage:true});
 await page.getByRole('button',{name:'退出登录',exact:true}).click();
 await page.getByRole('button',{name:'登录',exact:true}).waitFor();
 assert.equal(await page.getByRole('heading',{name:'browser-source.md',exact:true}).count(),0);
 console.log('PASS documents real browser: registration/project/upload/persisted QUEUED+PROCESSING/invalid type/recovery/persisted FAILED/mobile390/logout privacy');
 console.log('Screenshots: '+output);
} finally {
 await browser.close();
 if(projectId) {
  const cleanup=spawnSync('docker',['compose','-f',compose,'exec','-T','minio','sh','-c','mc alias set local http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc rm --recursive --force local/researchdesk-documents/projects/'+projectId+'/'],{encoding:'utf8',timeout:15000});
  assert.equal(cleanup.status,0,cleanup.stderr);
  sql("DELETE FROM business.storage_cleanup WHERE object_key LIKE 'projects/"+projectId+"/%'");
 }
 sql("DELETE FROM business.users WHERE email='"+email+"'; DELETE FROM business.auth_throttles WHERE key='email:"+email+"'");
}
