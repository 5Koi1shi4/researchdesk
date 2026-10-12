// Separate deterministic deadline fault fixture; never replaces the normal full journey.
import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {mkdir} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../../..');
const project=process.env.COMPOSE_PROJECT_NAME||'';
const base=process.env.ACCEPTANCE_URL||'';
const url=new URL(base);
assert.match(project,/^researchdesk-test-[a-z0-9-]+$/);
assert.equal(url.protocol,'http:');assert.ok(['localhost','127.0.0.1'].includes(url.hostname));
assert.ok(Number(url.port)>=18087&&Number(url.port)<=18999);assert.equal(url.pathname,'/');assert.ok(!url.search&&!url.hash&&!url.username);
function compose(...args){const result=spawnSync('docker',['compose','-p',project,'-f',path.join(root,'infra/compose.yaml'),...args],{encoding:'utf8',timeout:15000});assert.equal(result.status,0,result.stderr);return result.stdout.trim();}
assert.equal(compose('port','proxy','8080'),'127.0.0.1:'+url.port);
const output=path.join(root,'.cache/integration-browser');await mkdir(output,{recursive:true});
const browser=await chromium.launch({channel:'msedge',headless:true});const context=await browser.newContext();const page=await context.newPage();page.setDefaultTimeout(20000);
try{
 const auth=await context.request.post(base+'/api/auth/register',{data:{email:'timeout-'+randomUUID()+'@researchdesk.test',password:'TimeoutFixture9!'}});assert.equal(auth.status(),201);const session=await auth.json();const headers={'X-CSRF-Token':session.csrfToken};
 const created=await context.request.post(base+'/api/projects',{headers,data:{name:'Deadline browser fixture'}});assert.equal(created.status(),201);const p=await created.json();
 const started=await context.request.post(base+'/api/runs',{headers,data:{kind:'ANSWER',projectId:p.id,documentVersionIds:[],prompt:'Controlled deadline acceptance'}});assert.equal(started.status(),202);const run=await started.json();assert.match(run.runId,/^[a-f0-9-]{36}$/);
 await page.goto(base+'/?project='+p.id+'&run='+run.runId);await page.getByRole('button',{name:'研究问答',exact:true}).click();await page.getByText('Controlled draft for transaction race.',{exact:true}).waitFor();
 compose('exec','-T','postgres','psql','-U','researchdesk','-d','researchdesk','-v','ON_ERROR_STOP=1','-c',"UPDATE business.generation_runs SET deadline=now()-interval '1 second' WHERE id='"+run.runId+"' AND state='RUNNING'");
 await page.getByText('生成失败',{exact:true}).waitFor({timeout:20000});
 const response=await context.request.get(base+'/api/runs/'+run.runId);assert.equal(response.status(),200);const failed=await response.json();assert.equal(failed.state,'FAILED');assert.equal(failed.error.code,'RUN_DEADLINE_EXCEEDED');assert.ok(await page.getByRole('alert').filter({hasText:failed.error.message}).isVisible());
 assert.equal(await page.getByRole('button',{name:'停止生成',exact:true}).count(),0);assert.ok(await page.getByLabel('研究问题',{exact:true}).isEnabled());
 await page.reload();await page.getByText('生成失败',{exact:true}).waitFor();await page.screenshot({path:path.join(output,'timeout.png'),fullPage:true});
 console.log('PASS real deadline expiry → failed UI, restored snapshot and editable composer (controlled fault fixture)');
}finally{await context.close();await browser.close();}
