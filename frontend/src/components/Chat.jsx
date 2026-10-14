import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
// Подтверждения доставки копятся и уходят одним сообщением: на сервере это один UPDATE на всех.
const DELIVERED_FLUSH_DELAY = 100;
// Столько id сервер принимает в одном delivered (MessageService.MAX_DELIVERED_IDS).
const MAX_DELIVERED_IDS = 1000;

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
  const contactsRequestRef = useRef(0);
  const contactsReloadRef = useRef(null);
  const deliveredQueueRef = useRef([]);
  const deliveredTimerRef = useRef(null);

  chatsRef.current = chats;
  contactsRef.current = contacts;
  activePeerRef.current = activePeerId;
  searchRef.current = search;

  const updateChat = useCallback((peerId, fn) => {
    setChats((prev) => ({ ...prev, [peerId]: fn(prev[peerId] ?? EMPTY_CHAT) }));
  }, []);

  const loadContacts = useCallback(async (query) => {
    // Ответы могут прийти не в том порядке, в каком ушли запросы: набрали «a», потом «ab», а ответ на «a»
    // пришёл позже. Применяем только ответ на последний запрос, иначе устаревший список перетрёт свежий.
    const requestId = ++contactsRequestRef.current;
    try {
      const list = await api.contacts(auth.token, query);
      if (requestId !== contactsRequestRef.current) return;
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
  // Перезагрузка откладывается до конца текущего события: пачка из сотни сообщений от незнакомых
  // собеседников даёт один запрос, а не сотню.
  const bumpContact = useCallback((peerId, createdAt) => {
    if (contactsRef.current.some((c) => c.id === peerId)) {
      setContacts((prev) => touchContact(prev, peerId, createdAt));
    } else if (!searchRef.current.trim() && contactsReloadRef.current == null) {
      contactsReloadRef.current = setTimeout(() => {
        contactsReloadRef.current = null;
        loadContacts('');
      }, 0);
    }
  }, [loadContacts]);

  const flushDelivered = useCallback(() => {
    clearTimeout(deliveredTimerRef.current);
    deliveredTimerRef.current = null;
    const ids = deliveredQueueRef.current;
    deliveredQueueRef.current = [];
    // Если соединения сейчас нет, подтверждения теряются, и это не страшно: недоставленное сервер
    // пришлёт снова при переподключении, и оно будет подтверждено тогда.
    for (let i = 0; i < ids.length; i += MAX_DELIVERED_IDS) {
      socketRef.current?.send({ type: 'delivered', messageIds: ids.slice(i, i + MAX_DELIVERED_IDS) });
    }
  }, []);

  // immediately: пачку недоставленного подтверждаем сразу, потому что сервер ждёт подтверждения,
  // чтобы прислать следующую. Подтверждения обычных входящих копятся DELIVERED_FLUSH_DELAY мс.
  const confirmDelivered = useCallback((ids, immediately) => {
    deliveredQueueRef.current.push(...ids);
    if (immediately) {
      flushDelivered();
    } else if (deliveredTimerRef.current == null) {
      deliveredTimerRef.current = setTimeout(flushDelivered, DELIVERED_FLUSH_DELAY);
    }
  }, [flushDelivered]);

  // Входящее сообщение или своё из другой вкладки: в переписку и вверх списка контактов.
  const addMessage = useCallback((message) => {
    const peerId = message.senderId === me ? message.recipientId : message.senderId;
    updateChat(peerId, (chat) => ({ ...chat, messages: mergeMessage(chat.messages, { ...message, pending: false }) }));
    bumpContact(peerId, message.createdAt);
    return peerId;
  }, [me, updateChat, bumpContact]);

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
        const peerId = addMessage(message);
        if (message.senderId !== me) {
          confirmDelivered([message.id], false);
          if (activePeerRef.current === peerId && document.visibilityState === 'visible') {
            sendReadUpTo(peerId, message.id);
          } else {
            setContacts((prev) => prev.map((c) => (c.id === peerId ? { ...c, unread: c.unread + 1 } : c)));
          }
        }
        break;
      }
      case 'undelivered': {
        // Сообщения, пришедшие, пока мы были офлайн. Непрочитанные по ним сервер уже посчитал в списке
        // контактов, поэтому счётчики здесь не трогаем. Доставку подтверждаем всей пачкой сразу:
        // по этому подтверждению сервер пришлёт следующую.
        const lastIdByPeer = new Map();
        for (const message of event.messages) {
          lastIdByPeer.set(addMessage(message), message.id);
        }
        confirmDelivered(event.messages.map((m) => m.id), true);
        const active = activePeerRef.current;
        if (lastIdByPeer.has(active) && document.visibilityState === 'visible') {
          sendReadUpTo(active, lastIdByPeer.get(active));
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
      case 'presenceSnapshot': {
        // Ответ на подписку: состояние на момент подписки для всех, на кого подписались.
        const watched = new Set(event.watched);
        const online = new Set(event.online);
        const apply = (c) => (watched.has(c.id) ? { ...c, online: online.has(c.id) } : c);
        setContacts((prev) => prev.map(apply));
        setKnownUsers((prev) => Object.fromEntries(Object.entries(prev).map(([id, c]) => [id, apply(c)])));
        break;
      }
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
  }, [me, updateChat, sendReadUpTo, bumpContact, addMessage, confirmDelivered]);

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
    return () => {
      socket.close();
      clearTimeout(deliveredTimerRef.current);
      clearTimeout(contactsReloadRef.current);
    };
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

  // Подписка на онлайн-статусы: сервер присылает presence только по тем, на кого подписались,
  // то есть по пользователям из списка контактов и собеседнику открытого диалога.
  // Ключ не зависит от порядка, поэтому пересортировка списка не вызывает повторную подписку.
  const watchKey = useMemo(() => {
    const ids = new Set(contacts.filter((c) => !c.self).map((c) => c.id));
    if (activePeerId != null && activePeerId !== me) ids.add(activePeerId);
    return [...ids].sort((a, b) => a - b).join(',');
  }, [contacts, activePeerId, me]);

  // Подписываемся заново при каждом подключении: подписки живут, пока живёт соединение.
  useEffect(() => {
    if (!connected) return;
    const userIds = watchKey ? watchKey.split(',').map(Number) : [];
    socketRef.current?.send({ type: 'watch', userIds });
  }, [watchKey, connected]);

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
