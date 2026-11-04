export type User = {
  id: string;
  lrn_or_id: string;
  full_name: string;
  role: 'ADMIN' | 'TEACHER' | 'STUDENT';
};

export type AuthSession = { token: string; expires_at: number; user: User };

export class HubError extends Error {
  constructor(public code: string, public status: number) {
    super(code);
  }
}

export async function request<T>(path: string, token?: string, body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`http://127.0.0.1:8080${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: {
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      signal: AbortSignal.timeout(5000),
      cache: 'no-store',
    });
  } catch {
    throw new HubError('HUB_UNREACHABLE', 0);
  }
  let data: T & { error?: { code?: string } };
  try {
    data = await response.json();
  } catch {
    throw new HubError('INVALID_RESPONSE', response.status);
  }
  if (!response.ok) throw new HubError(data.error?.code ?? 'UNKNOWN', response.status);
  return data;
}

// A navigation hint only. The Hub always decides whether setup or login is allowed.
export function setupCompleted(value?: boolean): boolean {
  try {
    if (value !== undefined) localStorage.setItem('lara:admin-configured', String(value));
    return localStorage.getItem('lara:admin-configured') === 'true';
  } catch {
    return false;
  }
}
