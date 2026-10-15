async function request(path, { method = 'GET', body, token } = {}) {
  const headers = {};
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  const response = await fetch(path, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    const error = new Error(data.message || response.statusText);
    error.status = response.status;
    error.code = data.code;
    // Через сколько секунд сервер предлагает повторить запрос: приходит с 429 и 503.
    const retryAfter = Number(response.headers.get('Retry-After'));
    if (retryAfter > 0) {
      error.retryAfter = retryAfter;
    }
    throw error;
  }
  return response.status === 204 ? null : response.json();
}

export const api = {
  register: (login, password) =>
    request('/api/auth/register', { method: 'POST', body: { login, password } }),

  login: (login, password) =>
    request('/api/auth/login', { method: 'POST', body: { login, password } }),

  contacts: (token, search = '') =>
    request(`/api/users?search=${encodeURIComponent(search)}`, { token }),

  history: (token, peerId, before, limit = 50) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (before != null) {
      params.set('before', String(before));
    }
    return request(`/api/messages/${peerId}?${params}`, { token });
  },
};
