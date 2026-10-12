import type { components } from './generated/contracts';
export type Session = components['schemas']['Session'];
export type Project = components['schemas']['Project'];
export class ApiFailure extends Error { constructor(public status: number, message: string) { super(message); } }
let authGeneration = 0;
export const currentAuthGeneration = () => authGeneration;
export function invalidateAuthRequests() { authGeneration += 1; }

export async function api<T>(path: string, method = 'GET', body?: unknown, csrf?: string, options: { expireOnUnauthorized?: boolean } = {}): Promise<T> {
  // Auth mutations invalidate reads begun under the previous identity immediately.
  if (method === 'POST' && ['/api/auth/login', '/api/auth/register', '/api/auth/logout'].includes(path)) invalidateAuthRequests();
  const generation = currentAuthGeneration();
  const response = await fetch(path, { method, credentials: 'same-origin', cache: 'no-store', headers: { 'Content-Type': 'application/json', ...(csrf ? { 'X-CSRF-Token': csrf } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  if (!response.ok) {
    const error: components['schemas']['ApiError'] = await response.json();
    if (response.status === 401 && options.expireOnUnauthorized !== false && generation === currentAuthGeneration()) {
      invalidateAuthRequests();
      window.dispatchEvent(new Event('session-expired'));
    }
    throw new ApiFailure(response.status, error.message || '请求失败，请重试。');
  }
  return response.status === 204 ? undefined as T : response.json();
}
