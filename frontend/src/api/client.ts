/**
 * Thin fetch wrapper.
 *  - Sends the session token.
 *  - Tags every request with an X-Request-Id so a problem a user reports can be found in
 *    the server logs (the server reuses this ID on every log line for the request).
 *  - Turns the API's error shape into an ApiError the UI can show.
 */

const TOKEN_KEY = 'scheduler.token';

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string | null): void {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* private mode: session lasts until reload */
  }
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId: string;
  readonly details?: unknown;

  constructor(status: number, code: string, message: string, requestId: string, details?: unknown) {
    super(message);
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.details = details;
  }
}

let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn;
}

export async function api<T>(
  path: string,
  options: { method?: string; body?: unknown; headers?: Record<string, string> } = {},
): Promise<T> {
  const requestId = crypto.randomUUID();
  const token = getToken();
  let res: Response;
  try {
    res = await fetch(path, {
      method: options.method ?? 'GET',
      headers: {
        'X-Request-Id': requestId,
        ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...options.headers,
      },
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    });
  } catch {
    throw new ApiError(0, 'NETWORK', 'Cannot reach the server. Check your connection and try again.', requestId);
  }

  if (res.status === 204) return undefined as T;
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    const e = data?.error;
    if (res.status === 401 && token) onUnauthorized?.();
    throw new ApiError(res.status, e?.code ?? 'UNKNOWN', e?.message ?? `Request failed (${res.status})`, e?.requestId ?? requestId, e?.details);
  }
  return data as T;
}

export const get = <T,>(path: string) => api<T>(path);
export const post = <T,>(path: string, body?: unknown, headers?: Record<string, string>) =>
  api<T>(path, { method: 'POST', body: body ?? {}, headers });
export const patch = <T,>(path: string, body: unknown) => api<T>(path, { method: 'PATCH', body });
