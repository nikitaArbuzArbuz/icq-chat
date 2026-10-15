import { formatContactTime } from '../dates';
import { useMounted, useOneShot } from '../hooks';
import Avatar from './Avatar';
import { SearchIcon } from './Icons';

function UnreadBadge({ count, animate }) {
  const [bump, stop] = useOneShot(animate);
  return (
    <span className={`badge${bump ? ' bump' : ''}`} onAnimationEnd={stop}>
      {count > 99 ? '99+' : count}
      <span className="visually-hidden"> непрочитанных</span>
    </span>
  );
}

function ContactRow({ contact, active, onSelect }) {
  const mounted = useMounted();
  const online = !contact.self && contact.online;
  return (
    <li>
      <button
        type="button"
        className={`contact${active ? ' active' : ''}`}
        aria-current={active ? 'true' : undefined}
        onClick={() => onSelect(contact.id)}
      >
        <Avatar login={contact.login} self={contact.self} presence={online ? 'online' : undefined} />
        <span className="contact-name">
          {contact.self ? 'Избранное' : contact.login}
          {online && <span className="visually-hidden">, в сети</span>}
        </span>
        <span className="contact-side">
          {contact.lastMessageAt && (
            <time className="contact-time" dateTime={contact.lastMessageAt}>
              {formatContactTime(contact.lastMessageAt)}
            </time>
          )}
          {contact.unread > 0 && (
            // Новый key при каждом изменении числа: бейдж создаётся заново и «подпрыгивает».
            <UnreadBadge key={contact.unread} count={contact.unread} animate={mounted} />
          )}
        </span>
      </button>
    </li>
  );
}

export default function ContactList({ contacts, activePeerId, search, onSearch, onSelect }) {
  const query = search.trim();
  return (
    <div className="contacts">
      <label className="search">
        <SearchIcon />
        <input
          type="search"
          placeholder="Поиск по логину"
          aria-label="Поиск по логину"
          value={search}
          onChange={(e) => onSearch(e.target.value)}
        />
      </label>
      {contacts.length > 0 ? (
        <ul className="contact-list">
          {contacts.map((contact) => (
            <ContactRow
              key={contact.id}
              contact={contact}
              active={contact.id === activePeerId}
              onSelect={onSelect}
            />
          ))}
        </ul>
      ) : (
        query && <p className="list-empty">Нет пользователей, чей логин начинается с «{query}»</p>
      )}
    </div>
  );
}
