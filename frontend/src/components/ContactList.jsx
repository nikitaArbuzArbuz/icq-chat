export default function ContactList({ contacts, activePeerId, search, onSearch, onSelect }) {
  return (
    <aside className="contacts">
      <input
        className="search"
        placeholder="Поиск по логину"
        value={search}
        onChange={(e) => onSearch(e.target.value)}
      />
      <ul>
        {contacts.map((contact) => (
          <li
            key={contact.id}
            className={contact.id === activePeerId ? 'active' : ''}
            onClick={() => onSelect(contact.id)}
          >
            {!contact.self && <span className={`dot ${contact.online ? 'online' : 'offline'}`} />}
            <span className="name">{contact.self ? 'Избранное' : contact.login}</span>
            {contact.unread > 0 && <span className="badge">{contact.unread}</span>}
          </li>
        ))}
      </ul>
    </aside>
  );
}
