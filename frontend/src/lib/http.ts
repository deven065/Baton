// Thin fetch wrapper for the Spring Boot API (/api/*, proxied by Vite in dev).
const BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? '';
const TOKEN_KEY = 'baton.token';

// "Keep me signed in" keeps the token in localStorage; otherwise it lives in sessionStorage and ends with the tab.
let token: string | null = (() => { try { return localStorage.getItem(TOKEN_KEY) ?? sessionStorage.getItem(TOKEN_KEY); } catch { return null; } })();
const unauthListeners = new Set<() => void>();

export function setToken(t: string | null, remember = true) {
  token = t;
  try {
    localStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(TOKEN_KEY);
    if (t) (remember ? localStorage : sessionStorage).setItem(TOKEN_KEY, t);
  } catch { /* private mode: the token stays in memory only */ }
}
export const getToken = () => token;
export function onUnauthenticated(fn: () => void) { unauthListeners.add(fn); return () => { unauthListeners.delete(fn); }; }

/** Mirrors the backend's ApiError {error, message, details}. */
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public details?: unknown) { super(message); }
}

export async function api<T>(path: string, init: RequestInit & { json?: unknown } = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (token) headers.set('Authorization', `Bearer ${token}`);
  let body = init.body;
  if (init.json !== undefined) { headers.set('Content-Type', 'application/json'); body = JSON.stringify(init.json); }
  let res: Response;
  try {
    res = await fetch(BASE + path, { ...init, headers, body });
  } catch {
    throw new ApiError(0, 'NETWORK', 'Can’t reach the Baton API. Is the backend running on port 8080?');
  }
  if (res.status === 401 && token) unauthListeners.forEach(f => f());
  if (!res.ok) {
    let err: { error?: string; message?: string; details?: unknown } = {};
    try { err = await res.json(); } catch { /* not JSON */ }
    throw new ApiError(res.status, err.error ?? `HTTP_${res.status}`, err.message ?? res.statusText, err.details);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/**
 * Server-sent events over fetch, because EventSource cannot send the Authorization header.
 * Calls onEvent for each {event, data}; resolves when the stream ends. Abort with the signal.
 */
export async function streamEvents(path: string, onEvent: (event: string, data: unknown) => void, signal: AbortSignal) {
  const res = await fetch(BASE + path, { headers: { Authorization: `Bearer ${token ?? ''}`, Accept: 'text/event-stream' }, signal });
  if (!res.ok || !res.body) throw new ApiError(res.status, `HTTP_${res.status}`, 'Could not open the progress stream');
  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  let buf = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += value;
    let i: number;
    while ((i = buf.search(/\r?\n\r?\n/)) >= 0) {
      const block = buf.slice(0, i);
      buf = buf.slice(i).replace(/^\r?\n\r?\n/, '');
      let event = 'message';
      const data: string[] = [];
      for (const line of block.split(/\r?\n/)) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
      }
      if (!data.length) continue;
      const raw = data.join('\n');
      let parsed: unknown = raw;
      try { parsed = JSON.parse(raw); } catch { /* plain text */ }
      onEvent(event, parsed);
    }
  }
}

/** Downloads a file from the API with the session token (for exports). */
export async function download(path: string, fallbackName: string) {
  const headers = new Headers();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  const res = await fetch(BASE + path, { headers });
  if (!res.ok) {
    let err: { error?: string; message?: string } = {};
    try { err = await res.json(); } catch { /* not JSON */ }
    throw new ApiError(res.status, err.error ?? `HTTP_${res.status}`, err.message ?? res.statusText);
  }
  const name = /filename="?([^";]+)"?/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? fallbackName;
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Wakes the API (the free host sleeps when idle) while the person is still filling in a form. Never throws. */
export function wakeApi() {
  try { fetch(BASE + '/actuator/health', { mode: 'no-cors' }).catch(() => undefined); } catch { /* ignore */ }
}
