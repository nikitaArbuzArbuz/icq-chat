import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api';
import { ChatSocket } from '../chatSocket';
import ContactList from './ContactList';
import ChatWindow from './ChatWindow';

// Сообщения сортируются по серверному id; ещё не подтверждённые (без id) идут в конце.
function compareMessages(a, b) {
  if (a.id == null && b.id == null) return 0;
  if (a.id == null) return 1;
  if (b.id == null) return -1;
  return a.id - b.id;
}

function mergeMessage(list, msg) {
  const index = list.findIndex((m) =>
    (msg.id != null && m.id === msg.id) ||
    (msg.clientId && m.clientId === msg.clientId && m.senderId === msg.senderId));
  const next = [...list];
  if (index >= 0) {
    next[index] = { ...list[index], ...msg };
  } else {
    next.push(msg);
  }
  return next.sort(compareMessages);
}

function mergeAll(list, messages) {
  return messages.reduce((acc, m) => mergeMessage(acc, { ...m, pending: false }), list);
}

// Порядок контактов как в мессенджерах: «Избранное» первым, дальше диалоги по последнему сообщению
// от новых к старым, в конце пользователи без переписки в том порядке, в каком их отдал сервер.
function compareContacts(a, b) {
  if (a.self !== b.self) return a.self ? -1 : 1;
  const ta = a.lastMessageAt ? Date.parse(a.lastMessageAt) : null;
  const tb = b.lastMessageAt ? Date.parse(b.lastMessageAt) : null;
  if (ta === tb) return 0;
  if (ta == null) return 1;
  if (tb == null) return -1;
  return tb - ta;
}

// Запоминает время нового сообщения в диалоге и поднимает диалог вверх списка.
// Более старые события, например досылка недоставленного при подключении, порядок не меняют.
function touchContact(contacts, peerId, createdAt) {
  const index = contacts.findIndex((c) => c.id === peerId);
  if (index < 0 || !createdAt) return contacts;
  const contact = contacts[index];
  if (contact.lastMessageAt && Date.parse(contact.lastMessageAt) >= Date.parse(createdAt)) return contacts;
  const next = [...contacts];
  next[index] = { ...contact, lastMessageAt: createdAt };
  return next.sort(compareContacts);
}

const EMPTY_CHAT = { messages: [], hasMore: false, loaded: false, loading: false };

export default function Chat({ auth, onLogout }) {
  const me = auth.user.id;
  const [contacts, setContacts] = useState([]);
  // Все пользователи, которые попадали в список контактов. Открытый диалог берёт собеседника отсюда,
  // поэтому не закрывается, когда поиск убирает его из списка.
  const [knownUsers, setKnownUsers] = useState({});
  const [search, setSearch] = useState('');
  const [activePeerId, setActivePeerId] = useState(null);
  const [chats, setChats] = useState({});
  const [connected, setConnected] = useState(false);

  const socketRef = useRef(null);
  // clientId -> { to, text }: сообщения без подтверждения сохранения, переотправляются после переподключения.
  const pendingRef = useRef(new Map());
  const chatsRef = useRef(chats);
  const activePeerRef = useRef(activePeerId);
  const searchRef = useRef(search);
  const contactsRef = useRef(contacts);

  chatsRef.current = chats;
  contactsRef.current = contacts;
  activePeerRef.current = activePeerId;
  searchRef.current = search;

  const updateChat = useCallback((peerId, fn) => {
    setChats((prev) => ({ ...prev, [peerId]: fn(prev[peerId] ?? EMPTY_CHAT) }));
  }, []);

  const loadContacts = useCallback(async (query) => {
    try {
      const list = await api.contacts(auth.token, query);
      setContacts(list);
      setKnownUsers((prev) => ({ ...prev, ...Object.fromEntries(list.map((c) => [c.id, c])) }));
    } catch (e) {
      if (e.status === 401) onLogout();
    }
  }, [auth.token, onLogout]);

  useEffect(() => {
    const timer = setTimeout(() => loadContacts(search), 300);
    return () => clearTimeout(timer);
  }, [search, loadContacts]);

  // Новое сообщение в диалоге: поднимаем его вверх. Если собеседника нет в списке и поиск не активен,
  // перезагружаем список, чтобы диалог появился. При активном поиске он появится, когда поиск очистят.
  const bumpContact = useCallback((peerId, createdAt) => {
    if (contactsRef.current.some((c) => c.id === peerId)) {
      setContacts((prev) => touchContact(prev, peerId, createdAt));
    } else if (!searchRef.current.trim()) {
      loadContacts('');
    }
  }, [loadContacts]);

  const sendReadUpTo = useCallback((peerId, lastMessageId) => {
    if (peerId === me || lastMessageId == null) return;
    socketRef.current?.send({ type: 'read', peerId, lastMessageId });
    setContacts((prev) => prev.map((c) => (c.id === peerId ? { ...c, unread: 0 } : c)));
  }, [me]);

  const sendRead = useCallback((peerId) => {
    const chat = chatsRef.current[peerId];
    if (!chat) return;
    const incoming = chat.messages.filter((m) => m.senderId === peerId && m.id != null);
    if (incoming.length > 0) {
      sendReadUpTo(peerId, incoming[incoming.length - 1].id);
    }
  }, [sendReadUpTo]);

  const handleEvent = useCallback((event) => {
    switch (event.type) {
      case 'ack': {
        const pending = pendingRef.current.get(event.clientId);
        if (!pending) return;
        pendingRef.current.delete(event.clientId);
        updateChat(pending.to, (chat) => ({
          ...chat,
          messages: mergeMessage(chat.messages, {
            clientId: event.clientId,
            senderId: me,
            id: event.id,
            createdAt: event.createdAt,
            status: pending.to === me ? 'READ' : 'SENT',
            pending: false,
          }),
        }));
        bumpContact(pending.to, event.createdAt);
        break;
      }
      case 'message': {
        const message = event.message;
        const peerId = message.senderId === me ? message.recipientId : message.senderId;
        updateChat(peerId, (chat) => ({ ...chat, messages: mergeMessage(chat.messages, { ...message, pending: false }) }));
        bumpContact(peerId, message.createdAt);
        if (message.senderId !== me) {
          socketRef.current?.send({ type: 'delivered', messageIds: [message.id] });
          if (activePeerRef.current === peerId && document.visibilityState === 'visible') {
            sendReadUpTo(peerId, message.id);
          } else {
            setContacts((prev) => prev.map((c) => (c.id === peerId ? { ...c, unread: c.unread + 1 } : c)));
          }
        }
        break;
      }
      case 'delivered':
        updateChat(event.recipientId, (chat) => ({
          ...chat,
          messages: chat.messages.map((m) =>
            (event.messageIds.includes(m.id) && m.status === 'SENT' ? { ...m, status: 'DELIVERED' } : m)),
        }));
        break;
      case 'read':
        if (event.readerId === me) {
          // Прочитано в другой моей вкладке.
          setContacts((prev) => prev.map((c) => (c.id === event.senderId ? { ...c, unread: 0 } : c)));
        } else {
          updateChat(event.readerId, (chat) => ({
            ...chat,
            messages: chat.messages.map((m) =>
              (m.senderId === me && m.id != null && m.id <= event.upToMessageId ? { ...m, status: 'READ' } : m)),
          }));
        }
        break;
      case 'presence':
        setContacts((prev) => prev.map((c) => (c.id === event.userId ? { ...c, online: event.online } : c)));
        setKnownUsers((prev) => (prev[event.userId]
          ? { ...prev, [event.userId]: { ...prev[event.userId], online: event.online } }
          : prev));
        break;
      case 'error': {
        const pending = event.clientId ? pendingRef.current.get(event.clientId) : null;
        if (pending) {
          pendingRef.current.delete(event.clientId);
          updateChat(pending.to, (chat) => ({
            ...chat,
            messages: chat.messages.map((m) =>
              (m.clientId === event.clientId ? { ...m, pending: false, failed: true, error: event.message } : m)),
          }));
        } else {
          console.warn('Server error', event);
        }
        break;
      }
      default:
        break;
    }
  }, [me, updateChat, sendReadUpTo, bumpContact]);

  const handleEventRef = useRef(handleEvent);
  handleEventRef.current = handleEvent;
  const sendReadRef = useRef(sendRead);
  sendReadRef.current = sendRead;
  const loadContactsRef = useRef(loadContacts);
  loadContactsRef.current = loadContacts;

  useEffect(() => {
    const socket = new ChatSocket(auth.token, {
      onEvent: (event) => handleEventRef.current(event),
      onOpen: () => {
        setConnected(true);
        for (const [clientId, pending] of pendingRef.current) {
          socket.send({ type: 'send', clientId, to: pending.to, text: pending.text });
        }
        loadContactsRef.current(searchRef.current);
        if (activePeerRef.current != null) {
          sendReadRef.current(activePeerRef.current);
        }
      },
      onClose: () => setConnected(false),
      onUnauthorized: onLogout,
    });
    socketRef.current = socket;
    socket.connect();
    return () => socket.close();
  }, [auth.token, onLogout]);

  useEffect(() => {
    const onVisibility = () => {
      if (document.visibilityState === 'visible' && activePeerRef.current != null) {
        sendReadRef.current(activePeerRef.current);
      }
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, []);

  const openChat = async (peerId) => {
    setActivePeerId(peerId);
    const chat = chatsRef.current[peerId];
    if (chat?.loaded) {
      sendRead(peerId);
      return;
    }
    updateChat(peerId, (c) => ({ ...c, loading: true }));
    try {
      const page = await api.history(auth.token, peerId);
      updateChat(peerId, (c) => ({
        ...c,
        messages: mergeAll(c.messages, page.messages),
        hasMore: page.hasMore,
        loaded: true,
        loading: false,
      }));
      const incoming = page.messages.filter((m) => m.senderId === peerId);
      if (incoming.length > 0) {
        sendReadUpTo(peerId, incoming[incoming.length - 1].id);
      }
    } catch (e) {
      updateChat(peerId, (c) => ({ ...c, loading: false }));
      if (e.status === 401) onLogout();
    }
  };

  const loadOlder = async () => {
    const peerId = activePeerId;
    const chat = chatsRef.current[peerId];
    if (!chat?.hasMore || chat.loading) return;
    const oldest = chat.messages.find((m) => m.id != null);
    updateChat(peerId, (c) => ({ ...c, loading: true }));
    try {
      const page = await api.history(auth.token, peerId, oldest?.id);
      updateChat(peerId, (c) => ({
        ...c,
        messages: mergeAll(c.messages, page.messages),
        hasMore: page.hasMore,
        loading: false,
      }));
    } catch (e) {
      updateChat(peerId, (c) => ({ ...c, loading: false }));
    }
  };

  const sendMessage = (text) => {
    const peerId = activePeerId;
    const clientId = crypto.randomUUID();
    pendingRef.current.set(clientId, { to: peerId, text });
    updateChat(peerId, (chat) => ({
      ...chat,
      messages: mergeMessage(chat.messages, {
        clientId,
        senderId: me,
        recipientId: peerId,
        text,
        pending: true,
        createdAt: new Date().toISOString(),
      }),
    }));
    socketRef.current?.send({ type: 'send', clientId, to: peerId, text });
  };

  const activePeer = contacts.find((c) => c.id === activePeerId) ?? knownUsers[activePeerId] ?? null;

  return (
    <div className="app">
      <div className="topbar">
        <span>Вы вошли как <b>{auth.user.login}</b></span>
        <span className={`connection ${connected ? 'ok' : 'lost'}`}>
          {connected ? 'Подключено' : 'Переподключение…'}
        </span>
        <button onClick={onLogout}>Выйти</button>
      </div>
      <div className="layout">
        <ContactList
          contacts={contacts}
          activePeerId={activePeerId}
          search={search}
          onSearch={setSearch}
          onSelect={openChat}
        />
        <ChatWindow
          me={me}
          peer={activePeer}
          chat={chats[activePeerId]}
          onSend={sendMessage}
          onLoadOlder={loadOlder}
        />
      </div>
    </div>
  );
}
