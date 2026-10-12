import { UsagePanel } from '../usage/UsagePanel';
import { SourceViewer } from '../documents/SourceViewer';
import { ReportPage } from '../reports/ReportPage';
import { allowReportLeave } from '../reports/reportsApi';
import { ResearchPage } from '../research/ResearchPage';
import { DocumentPage } from '../documents/DocumentPage';
import { useEffect, useState, type FormEvent } from 'react';
import { api, type Project, type Session } from '../../api';
export function ProjectPage({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const [projects, setProjects] = useState<Project[]>([]), [selected, setSelected] = useState<Project | null>(null), [name, setName] = useState(''), [error, setError] = useState(''), [busy, setBusy] = useState(false), [loading, setLoading] = useState(true);
  const [generatedRunId,setGeneratedRunId]=useState<string>();
  const [tab, setTab] = useState<'documents'|'research'|'reports'>(()=>new URL(window.location.href).searchParams.has('run')?'research':'documents');
  function back() { if (!allowReportLeave()) return; setGeneratedRunId(undefined); setSelected(null); const url=new URL(window.location.href); url.searchParams.delete('project'); url.searchParams.delete('run'); window.history.replaceState({},'',url); }
  useEffect(() => { let active=true; const id=new URL(window.location.href).searchParams.get('project'); if(id)void api<Project>('/api/projects/'+encodeURIComponent(id)).then(value=>{if(active)setSelected(value);}).catch(e=>{if(active)setError(e.message);}); return()=>{active=false;}; }, []);
  useEffect(() => { let active = true; api<Project[]>('/api/projects').then(result => { if (active) setProjects(result); }).catch(failure => { if (active) setError(failure.message); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, []);
  async function create(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try { const project = await api<Project>('/api/projects', 'POST', { name }, session.csrfToken); setProjects(existing => [project, ...existing]); setName(''); }
    catch (failure) { setError(failure instanceof Error ? failure.message : '连接失败，请重试。'); } finally { setBusy(false); }
  }
  async function open(project: Project) { setBusy(true); setError(''); try { setSelected(await api<Project>(`/api/projects/${project.id}`)); setTab('documents'); setGeneratedRunId(undefined); const url=new URL(window.location.href); url.searchParams.set('project',project.id); url.searchParams.delete('run'); window.history.replaceState({},'',url); } catch (failure) { setError(failure instanceof Error ? failure.message : '连接失败，请重试。'); } finally { setBusy(false); } }
  async function logout() { if (!allowReportLeave()) return; setBusy(true); setError(''); try { await api<void>('/api/auth/logout', 'POST', undefined, session.csrfToken); onLogout(); } catch (failure) { setError(failure instanceof Error ? failure.message : '连接失败，请重试。'); } finally { setBusy(false); } }
  return <div className="workspace"><aside className="sidebar"><p className="brand">ResearchDesk</p><nav aria-label="主导航"><button className="nav-current" onClick={back}>我的项目</button></nav><div className="account"><p>{session.user.email}</p><button className="secondary" disabled={busy} onClick={logout}>退出登录</button></div></aside>
    <main className="project-main"><p className="eyebrow">私人研究空间</p><h1>{selected ? selected.name : '我的项目'}</h1>{error ? <p role="alert" className="error">{error}</p> : null}
    {selected ? <><button className="text-button" onClick={back}>返回项目列表</button><nav className="project-tabs" aria-label="项目工具"><button className={tab==='documents'?'':'secondary'} aria-pressed={tab==='documents'} onClick={()=>{if(allowReportLeave())setTab('documents');}}>项目资料</button><button className={tab==='research'?'':'secondary'} aria-pressed={tab==='research'} onClick={()=>{if(allowReportLeave())setTab('research');}}>研究问答</button><button className={tab==='reports'?'':'secondary'} aria-pressed={tab==='reports'} onClick={()=>{if(allowReportLeave()){setGeneratedRunId(undefined);setTab('reports');}}}>报告</button></nav>{tab==='documents'?<DocumentPage key={selected.id} project={selected} session={session} />:tab==='reports'?<ReportPage key={selected.id} projectId={selected.id} csrfToken={session.csrfToken} generatedRunId={generatedRunId} />:<ResearchPage key={selected.id} project={selected} session={session} renderSource={citation=><SourceViewer citation={citation}/>} onOpenReports={runId=>{setGeneratedRunId(runId);setTab('reports');}} />}<UsagePanel projectId={selected.id} refreshKey={tab}/></> : <><p>一个项目，一个研究主题。资料和研究记录将在这里汇集。</p>
    <form className="project-create" onSubmit={create}><div><label htmlFor="project-name">新项目名称</label><input id="project-name" required maxLength={120} value={name} onChange={e => setName(e.target.value)} placeholder="例如：数据库索引策略" /></div><button type="submit" disabled={busy}>创建项目</button></form>
    {loading ? <p role="status">正在加载项目…</p> : projects.length === 0 ? <section className="empty-state"><h2>从一个研究主题开始</h2><p>创建你的第一个项目，随后在项目内添加资料。</p></section> : <ul className="project-list">{projects.map(project => <li key={project.id}><button disabled={busy} onClick={() => open(project)}><span className="project-title">{project.name}</span><span className="caption">创建于 {new Date(project.createdAt).toLocaleDateString('zh-CN')}</span><span className="open-label">打开项目 →</span></button></li>)}</ul>}</>}
    </main></div>;
}
