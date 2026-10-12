import { useCallback, useEffect, useState } from 'react';
import { api, ApiFailure, currentAuthGeneration, invalidateAuthRequests, type Session } from './api';
import { LoginPage } from './features/auth/LoginPage';
import { ProjectPage } from './features/projects/ProjectPage';

export function App() {
  const [session, setSession] = useState<Session | null>(null), [loading, setLoading] = useState(true), [error, setError] = useState('');
  const changeSession = useCallback((next: Session | null) => {
    invalidateAuthRequests();
    setSession(next); setError(''); setLoading(false);
  }, []);
  useEffect(() => {
    let active = true;
    let latestRefresh = 0;
    const expired = () => changeSession(null);
    const refresh = async () => {
      const generation = currentAuthGeneration();
      const request = ++latestRefresh;
      const isCurrent = () => active && generation === currentAuthGeneration() && request === latestRefresh;
      try {
        // Refresh owns its 401 handling so an older request cannot expire a newer one.
        const value = await api<Session>('/api/auth/session', 'GET', undefined, undefined, { expireOnUnauthorized: false });
        if (isCurrent()) { setSession(value); setError(''); }
      } catch (failure) {
        if (!isCurrent()) return;
        if (failure instanceof ApiFailure && failure.status === 401) changeSession(null);
        else { setSession(null); setError('无法连接工作台，请检查连接后重试。'); }
      } finally {
        if (isCurrent()) setLoading(false);
      }
    };
    window.addEventListener('session-expired', expired); window.addEventListener('focus', refresh); void refresh();
    return () => { active = false; window.removeEventListener('session-expired', expired); window.removeEventListener('focus', refresh); };
  }, [changeSession]);
  if (loading) return <main className="loading" role="status">正在打开工作台…</main>;
  if (error) return <main className="loading"><p role="alert" className="error">{error}</p><button onClick={() => location.reload()}>重新连接</button></main>;
  return session ? <ProjectPage key={session.user.id} session={session} onLogout={() => changeSession(null)} /> : <LoginPage onSession={changeSession} />;
}
