import { useLayoutEffect, useRef } from 'react';
import MessageStatus from './MessageStatus';
import { dayKey, formatDay, formatTime } from '../dates';
import { useOneShot } from '../hooks';

// Сообщения одного автора с паузой меньше этой собираются в группу: отступ между ними меньше,
// и пузыри прилегают друг к другу.
const GROUP_GAP_MS = 5 * 60 * 1000;

// Ключ не меняется, когда сервер подтверждает сохранение и у сообщения появляется id.
// Поэтому React не пересоздаёт пузырь и не проигрывает анимацию появления второй раз.
function messageKey(m) {
  return m.clientId ? `${m.senderId}:${m.clientId}` : `id:${m.id}`;
}

function MessageBubble({ message, own, grouped, showStatus, animate }) {
  const [enter, stop] = useOneShot(animate);
  const classes = [
    'message',
    own ? 'own' : 'incoming',
    grouped && 'grouped',
    showStatus && 'with-status',
    message.failed && 'failed',
    enter && 'enter',
  ].filter(Boolean).join(' ');
  return (
    // Анимации галочек внутри пузыря тоже присылают animationend сюда: снимаем класс только по своей.
    <div className={classes} onAnimationEnd={(e) => e.target === e.currentTarget && stop()}>
      <span className="text">{message.text}</span>
      {message.failed && <span className="send-error">Не отправлено: {message.error}</span>}
      <span className="meta">
        <time dateTime={message.createdAt}>{formatTime(message.createdAt)}</time>
        {showStatus && <MessageStatus message={message} />}
      </span>
    </div>
  );
}

/**
 * Лента одного диалога: разделители по дням, группы сообщений, прокрутка и подгрузка старых сообщений.
 * ChatWindow создаёт её заново при смене собеседника (key), поэтому все refs здесь относятся к одному диалогу.
 */
export default function MessageList({ me, peer, chat, onLoadOlder }) {
  const listRef = useRef(null);
  const prevScrollHeight = useRef(null);
  const lastMessageKey = useRef(null);
  // Ключи сообщений, которые уже показаны, и тех, что пришли после открытия диалога.
  // Новые пузыри въезжают с анимацией, история появляется сразу.
  const seenKeys = useRef(null);
  const enteringKeys = useRef(new Set());

  const messages = chat?.messages ?? [];
  const last = messages[messages.length - 1];
  const currentLastKey = last ? messageKey(last) : null;

  if (seenKeys.current == null) {
    if (chat?.loaded) {
      seenKeys.current = new Set(messages.map(messageKey));
    }
  } else {
    for (const m of messages) {
      const key = messageKey(m);
      if (!seenKeys.current.has(key)) {
        seenKeys.current.add(key);
        // Старые сообщения, подгруженные прокруткой вверх, появляются без анимации.
        if (prevScrollHeight.current == null) {
          enteringKeys.current.add(key);
        }
      }
    }
  }

  useLayoutEffect(() => {
    const list = listRef.current;
    if (!list) {
      return;
    }
    if (prevScrollHeight.current != null) {
      // Старые сообщения добавились сверху: сохраняем видимую позицию прокрутки.
      list.scrollTop = list.scrollHeight - prevScrollHeight.current;
      prevScrollHeight.current = null;
    } else if (currentLastKey !== lastMessageKey.current) {
      list.scrollTop = list.scrollHeight;
    }
    lastMessageKey.current = currentLastKey;
  }, [messages, currentLastKey]);

  const handleScroll = () => {
    const list = listRef.current;
    if (list && list.scrollTop < 50 && chat?.hasMore && !chat.loading) {
      prevScrollHeight.current = list.scrollHeight;
      onLoadOlder();
    }
  };

  const rows = [];
  let prev = null;
  let prevDay = null;
  for (const m of messages) {
    const day = dayKey(m.createdAt);
    // Разделитель ставится только при переходе на более поздний день: так подпись дня не повторится,
    // даже если время неподтверждённого сообщения, взятое с часов клиента, расходится с серверным.
    if (prevDay == null || day > prevDay) {
      rows.push(
        <div key={`day-${day}`} className="day-separator">
          {formatDay(m.createdAt)}
        </div>,
      );
      prevDay = day;
      prev = null;
    }
    const own = m.senderId === me;
    const grouped = prev != null && prev.senderId === m.senderId
      && Date.parse(m.createdAt) - Date.parse(prev.createdAt) < GROUP_GAP_MS;
    // В «Избранном» нет доставки и прочтения: там статус виден, только пока сообщение отправляется.
    const showStatus = own && (!peer.self || m.pending);
    const key = messageKey(m);
    rows.push(
      <MessageBubble
        key={key}
        message={m}
        own={own}
        grouped={grouped}
        showStatus={showStatus}
        animate={enteringKeys.current.has(key)}
      />,
    );
    prev = m;
  }

  return (
    <div className="messages" ref={listRef} onScroll={handleScroll}>
      {chat?.loading && (
        <div className="loading" role="status">
          <span className="spinner" aria-hidden="true" />
          Загрузка…
        </div>
      )}
      {chat?.loaded && messages.length === 0 && (
        <div className="empty-state">
          <p className="empty-title">{peer.self ? 'Здесь пока пусто' : 'Сообщений пока нет'}</p>
          <p className="empty-hint">
            {peer.self
              ? 'Пишите сюда заметки и ссылки: их видите только вы'
              : 'Начните переписку: сообщение дойдёт, даже если собеседник не в сети'}
          </p>
        </div>
      )}
      {rows}
    </div>
  );
}
