import { useState } from 'react';
import { api } from '../api';

export default function Login({ onAuth }) {
  const [mode, setMode] = useState('login');
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const data = mode === 'login'
        ? await api.login(login, password)
        : await api.register(login, password);
      onAuth(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-page">
      <form className="login-form" onSubmit={submit}>
        <h1>ICQ Chat</h1>
        <div className="tabs">
          <button type="button" className={mode === 'login' ? 'active' : ''} onClick={() => setMode('login')}>
            Вход
          </button>
          <button type="button" className={mode === 'register' ? 'active' : ''} onClick={() => setMode('register')}>
            Регистрация
          </button>
        </div>
        <input
          placeholder="Логин"
          value={login}
          onChange={(e) => setLogin(e.target.value)}
          autoComplete="username"
        />
        <input
          type="password"
          placeholder="Пароль"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
        />
        {error && <div className="error">{error}</div>}
        <button type="submit" disabled={loading}>
          {mode === 'login' ? 'Войти' : 'Зарегистрироваться'}
        </button>
        <p className="hint">Тестовые пользователи: alice / password123, bob / password123</p>
      </form>
    </div>
  );
}
