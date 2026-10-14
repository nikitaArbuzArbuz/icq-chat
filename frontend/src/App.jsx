import { useCallback, useState } from 'react';
import Login from './components/Login';
import Chat from './components/Chat';

const STORAGE_KEY = 'icq-chat-auth';

function loadAuth() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export default function App() {
  const [auth, setAuth] = useState(loadAuth);

  const handleAuth = useCallback((data) => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
    setAuth(data);
  }, []);

  const handleLogout = useCallback(() => {
    localStorage.removeItem(STORAGE_KEY);
    setAuth(null);
  }, []);

  if (!auth) {
    return <Login onAuth={handleAuth} />;
  }
  return <Chat auth={auth} onLogout={handleLogout} />;
}
