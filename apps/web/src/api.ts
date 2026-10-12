import type { components } from './generated/contracts';
export type Session = components['schemas']['Session'];
export type Project = components['schemas']['Project'];
export class ApiFailure extends Error { constructor(public status: number, message: string) { super(message); } }
export async function api<T>(path: string, method = 'GET', body?: unknown, csrf?: string): Promise<T> {
  const response = await fetch(path, { method, credentials: 'same-origin', cache: 'no-store', headers: { 'Content-Type': 'application/json', ...(csrf ? { 'X-CSRF-Token': csrf } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  if (!response.ok) {
    const error: components['schemas']['ApiError'] = await response.json();
    if (response.status === 401) window.dispatchEvent(new Event('session-expired'));
    throw new ApiFailure(response.status, error.message || '请求失败，请重试。');
  }
  return response.status === 204 ? undefined as T : response.json();
}
