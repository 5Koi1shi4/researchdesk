import { useState, type FormEvent } from 'react';
import { api, type Session } from '../../api';
export function LoginPage({ onSession }: { onSession: (session: Session) => void }) {
  const [register, setRegister] = useState(false), [email, setEmail] = useState(''), [password, setPassword] = useState(''), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try { onSession(await api<Session>(`/api/auth/${register ? 'register' : 'login'}`, 'POST', { email, password })); }
    catch (failure) { setError(failure instanceof Error ? failure.message : '连接失败，请重试。'); }
    finally { setBusy(false); }
  }
  return <main className="auth-layout"><section className="auth-intro"><p className="eyebrow">RESEARCHDESK / 研究工作台</p><h1>让每个结论<br />有据可查。</h1><p>按研究项目整理资料，保留从问题到原文的线索。</p><p className="caption">当前提供私有项目管理与资料上传。研究问答与报告功能即将开放。</p></section>
    <section className="auth-panel" aria-labelledby="auth-title"><h2 id="auth-title">{register ? '创建账号' : '登录工作台'}</h2><p>项目仅对你的账号可见。</p><form onSubmit={submit}>
      <label htmlFor="email">邮箱</label><input id="email" type="email" autoComplete="username" required maxLength={254} value={email} onChange={e => setEmail(e.target.value)} />
      <label htmlFor="password">密码</label><input id="password" type="password" autoComplete={register ? 'new-password' : 'current-password'} required minLength={12} maxLength={72} value={password} onChange={e => setPassword(e.target.value)} aria-describedby="password-help" />
      <p id="password-help" className="caption">至少 12 个字符，最多 72 字节；中文等字符会占用多个字节。</p>{error ? <p role="alert" className="error">{error}</p> : null}
      <button disabled={busy} type="submit">{busy ? '正在处理…' : register ? '注册并进入' : '登录'}</button></form>
      <button className="text-button" disabled={busy} onClick={() => { setRegister(!register); setError(''); setPassword(''); }}>{register ? '已有账号？返回登录' : '没有账号？创建账号'}</button>
      <p className="caption">邮箱仅用于登录，无邮箱验证和密码找回。请妥善保存密码。</p></section></main>;
}
