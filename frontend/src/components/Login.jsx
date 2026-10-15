import { useState } from 'react';
import { api } from '../api';
import { LogoMark } from './Icons';
import ThemeToggle from './ThemeToggle';

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
      <div className="login-corner">
        <ThemeToggle />
      </div>
      <form className="login-card" onSubmit={submit}>
        <LogoMark className="login-mark" />
        <h1>ICQ Chat</h1>
        {/* Подложка-«ползунок» переезжает под выбранную кнопку (см. .segmented-thumb в styles.css). */}
        <div className="segmented" data-mode={mode} role="group" aria-label="Вход или регистрация">
          <span className="segmented-thumb" aria-hidden="true" />
          <button type="button" aria-pressed={mode === 'login'} onClick={() => setMode('login')}>
            Вход
          </button>
          <button type="button" aria-pressed={mode === 'register'} onClick={() => setMode('register')}>
            Регистрация
          </button>
        </div>
        <input
          placeholder="Логин"
          aria-label="Логин"
          value={login}
          onChange={(e) => setLogin(e.target.value)}
          autoComplete="username"
        />
        <input
          type="password"
          placeholder="Пароль"
          aria-label="Пароль"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
        />
        {error && <div className="error" role="alert">{error}</div>}
        <button type="submit" className="primary" disabled={loading}>
          {mode === 'login' ? 'Войти' : 'Зарегистрироваться'}
        </button>
        <p className="hint">
          Тестовые пользователи: alice и bob
          <br />
          Пароль у обоих: password123
        </p>
      </form>
    </div>
  );
}
