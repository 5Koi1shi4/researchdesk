import { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { api, ApiFailure, type Session } from './api';
import { LoginPage } from './features/auth/LoginPage';
import { ProjectPage } from './features/projects/ProjectPage';
import './style.css';

function App() {
  const [session, setSession] = useState<Session | null>(null), [loading, setLoading] = useState(true), [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    const expired = () => { setSession(null); setError(''); };
    const refresh = () => api<Session>('/api/auth/session').then(value => { if (active) { setSession(value); setError(''); } }).catch(failure => { if (active) { setSession(null); if (!(failure instanceof ApiFailure && failure.status === 401)) setError('无法连接工作台，请检查连接后重试。'); } }).finally(() => { if (active) setLoading(false); });
    window.addEventListener('session-expired', expired); window.addEventListener('focus', refresh); void refresh();
    return () => { active = false; window.removeEventListener('session-expired', expired); window.removeEventListener('focus', refresh); };
  }, []);
  if (loading) return <main className="loading" role="status">正在打开工作台…</main>;
  if (error) return <main className="loading"><p role="alert" className="error">{error}</p><button onClick={() => location.reload()}>重新连接</button></main>;
  return session ? <ProjectPage key={session.user.id} session={session} onLogout={() => setSession(null)} /> : <LoginPage onSession={setSession} />;
}
createRoot(document.getElementById('root')!).render(<App />);
