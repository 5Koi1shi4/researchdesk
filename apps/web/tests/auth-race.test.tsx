// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../src/App';

const oldSession = { user: { id: 'old-user', email: 'old@example.test' }, csrfToken: 'old-csrf' };
const newSession = { user: { id: 'new-user', email: 'new@example.test' }, csrfToken: 'new-csrf' };
const project = { id: 'project-1', name: '私有项目', createdAt: '2026-10-12T00:00:00Z' };
const response = (body: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const unauthorized = () => response({ code: 'UNAUTHENTICATED', message: '请登录后继续。', requestId: 'request-1' }, 401);
let pending: ((value: Response) => void)[];
function setup(initiallyAuthenticated = true) {
  let initial = true;
  vi.stubGlobal('fetch', vi.fn((path: string, options: RequestInit) => {
    if (path === '/api/auth/session') {
      if (initial) { initial = false; return Promise.resolve(initiallyAuthenticated ? response(oldSession) : unauthorized()); }
      return new Promise<Response>(resolve => pending.push(resolve));
    }
    if (path === '/api/projects') return Promise.resolve(response([project]));
    if (path === '/api/auth/logout') return Promise.resolve(response(null, 204));
    if (path === '/api/auth/login' && options.method === 'POST') return Promise.resolve(response(newSession));
    throw new Error(`Unexpected actual component request: ${path}`);
  }));
  render(<App />);
}
async function release(index: number, value: Response) { await act(async () => { pending[index](value); }); }
async function refresh() { fireEvent(window, new Event('focus')); await waitFor(() => expect(pending.length).toBeGreaterThan(0)); }
beforeEach(() => { pending = []; });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('auth transitions discard delayed session responses', () => {
  it('does not restore private UI from a refresh that started before logout', async () => {
    setup(); await screen.findByText('私有项目'); await refresh();
    fireEvent.click(screen.getByRole('button', { name: '退出登录', exact: true }));
    await screen.findByRole('button', { name: '登录', exact: true });
    await release(0, response(oldSession));
    expect(screen.queryByText('私有项目')).toBeNull();
    expect(screen.getByRole('button', { name: '登录', exact: true })).toBeTruthy();
  });
  it('does not restore private UI after a session-expired transition', async () => {
    setup(); await screen.findByText('私有项目'); await refresh();
    act(() => window.dispatchEvent(new Event('session-expired')));
    await screen.findByRole('button', { name: '登录', exact: true });
    await release(0, response(oldSession));
    expect(screen.queryByText('私有项目')).toBeNull();
    expect(screen.getByRole('button', { name: '登录', exact: true })).toBeTruthy();
  });
  it('does not erase a new login when an earlier refresh returns 401', async () => {
    setup(false); await screen.findByRole('button', { name: '登录', exact: true }); await refresh();
    fireEvent.change(screen.getByLabelText('邮箱'), { target: { value: 'new@example.test' } });
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'CorrectHorseBattery9!' } });
    fireEvent.click(screen.getByRole('button', { name: '登录', exact: true }));
    await screen.findByText('new@example.test');
    await release(0, unauthorized());
    expect(screen.queryByRole('button', { name: '登录', exact: true })).toBeNull();
    expect(screen.getByText('new@example.test')).toBeTruthy();
  });
  it('applies only the newest focus refresh when responses arrive in reverse order', async () => {
    setup(); await screen.findByText('私有项目'); await refresh();
    fireEvent(window, new Event('focus')); await waitFor(() => expect(pending).toHaveLength(2));
    await release(1, response(newSession)); await screen.findByText('new@example.test');
    await release(0, response(oldSession));
    expect(screen.queryByText('old@example.test')).toBeNull();
    expect(screen.getByText('new@example.test')).toBeTruthy();
  });
});
