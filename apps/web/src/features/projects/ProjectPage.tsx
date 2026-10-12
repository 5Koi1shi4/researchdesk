import { DocumentPage } from '../documents/DocumentPage';
import { useEffect, useState, type FormEvent } from 'react';
import { api, type Project, type Session } from '../../api';
export function ProjectPage({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const [projects, setProjects] = useState<Project[]>([]), [selected, setSelected] = useState<Project | null>(null), [name, setName] = useState(''), [error, setError] = useState(''), [busy, setBusy] = useState(false), [loading, setLoading] = useState(true);
  useEffect(() => { let active = true; api<Project[]>('/api/projects').then(result => { if (active) setProjects(result); }).catch(failure => { if (active) setError(failure.message); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, []);
  async function create(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try { const project = await api<Project>('/api/projects', 'POST', { name }, session.csrfToken); setProjects(existing => [project, ...existing]); setName(''); }
    catch (failure) { setError(failure instanceof Error ? failure.message : '连接失败，请重试。'); } finally { setBusy(false); }
  }
  async function open(project: Project) { setBusy(true); setError(''); try { setSelected(await api<Project>(`/api/projects/${project.id}`)); } catch (failure) { setError(failure instanceof Error ? failure.message : '连接失败，请重试。'); } finally { setBusy(false); } }
  async function logout() { setBusy(true); setError(''); try { await api<void>('/api/auth/logout', 'POST', undefined, session.csrfToken); onLogout(); } catch (failure) { setError(failure instanceof Error ? failure.message : '连接失败，请重试。'); } finally { setBusy(false); } }
  return <div className="workspace"><aside className="sidebar"><p className="brand">ResearchDesk</p><nav aria-label="主导航"><button className="nav-current" onClick={() => setSelected(null)}>我的项目</button></nav><div className="account"><p>{session.user.email}</p><button className="secondary" disabled={busy} onClick={logout}>退出登录</button></div></aside>
    <main className="project-main"><p className="eyebrow">私人研究空间</p><h1>{selected ? selected.name : '我的项目'}</h1>{error ? <p role="alert" className="error">{error}</p> : null}
    {selected ? <><button className="text-button" onClick={() => setSelected(null)}>返回项目列表</button><DocumentPage key={selected.id} project={selected} session={session} /></> : <><p>一个项目，一个研究主题。资料和研究记录将在这里汇集。</p>
    <form className="project-create" onSubmit={create}><div><label htmlFor="project-name">新项目名称</label><input id="project-name" required maxLength={120} value={name} onChange={e => setName(e.target.value)} placeholder="例如：数据库索引策略" /></div><button type="submit" disabled={busy}>创建项目</button></form>
    {loading ? <p role="status">正在加载项目…</p> : projects.length === 0 ? <section className="empty-state"><h2>从一个研究主题开始</h2><p>创建你的第一个项目，随后在项目内添加资料。</p></section> : <ul className="project-list">{projects.map(project => <li key={project.id}><button disabled={busy} onClick={() => open(project)}><span className="project-title">{project.name}</span><span className="caption">创建于 {new Date(project.createdAt).toLocaleDateString('zh-CN')}</span><span className="open-label">打开项目 →</span></button></li>)}</ul>}</>}
    </main></div>;
}
