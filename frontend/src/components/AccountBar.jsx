import Avatar from './Avatar';
import { LogoutIcon } from './Icons';
import ThemeToggle from './ThemeToggle';

/** Нижняя строка под списком контактов: кто вошёл, есть ли соединение, смена темы и выход. */
export default function AccountBar({ login, connected, onLogout }) {
  return (
    <div className="account">
      <Avatar login={login} presence={connected ? 'online' : 'connecting'} size="sm" />
      <div className="account-text">
        <span className="account-name">{login}</span>
        <span className={`account-status ${connected ? 'ok' : 'lost'}`} role="status">
          {connected ? 'в сети' : 'подключение…'}
        </span>
      </div>
      <ThemeToggle />
      <button type="button" className="icon-button" onClick={onLogout} aria-label="Выйти" title="Выйти">
        <LogoutIcon />
      </button>
    </div>
  );
}
