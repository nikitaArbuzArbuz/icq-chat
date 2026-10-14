import { useLayoutEffect, useRef, useState } from 'react';
import MessageStatus from './MessageStatus';

const MAX_LENGTH = 4000;
// Текст без единого видимого символа: обычные и неразрывные пробелы, пробелы нулевой ширины
// и другие форматирующие символы, хангыльские заполнители, пустой символ Брайля, селекторы начертания.
// Та же проверка есть на сервере (Texts.isVisuallyBlank), здесь она нужна, чтобы не отправлять заведомо пустое.
const VISUALLY_BLANK = /^[\s\p{Cc}\p{Cf}\u034F\u115F\u1160\u3164\uFFA0\u2800\uFE00-\uFE0F\u{1D159}\u{E0100}-\u{E01EF}]*$/u;

function formatTime(iso) {
  if (!iso) {
    return '';
  }
  return new Date(iso).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
}

export default function ChatWindow({ me, peer, chat, onSend, onLoadOlder }) {
  const [text, setText] = useState('');
  const listRef = useRef(null);
  const prevScrollHeight = useRef(null);
  const lastMessageKey = useRef(null);

  const messages = chat?.messages ?? [];
  const last = messages[messages.length - 1];
  const currentLastKey = last ? (last.id ?? last.clientId) : null;

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

  const submit = (e) => {
    e.preventDefault();
    const value = text.trim();
    if (VISUALLY_BLANK.test(value)) {
      return;
    }
    onSend(value);
    setText('');
  };

  if (!peer) {
    return <section className="chat empty">Выберите собеседника слева</section>;
  }

  return (
    <section className="chat">
      <header className="chat-header">
        {peer.self ? 'Избранное' : peer.login}
        {!peer.self && <span className="presence">{peer.online ? 'в сети' : 'не в сети'}</span>}
      </header>
      <div className="messages" ref={listRef} onScroll={handleScroll}>
        {chat?.loading && <div className="loading">Загрузка…</div>}
        {messages.map((m) => {
          const own = m.senderId === me;
          return (
            <div key={m.id ?? m.clientId} className={`message ${own ? 'own' : 'incoming'}`}>
              <div className="text">{m.text}</div>
              <div className="meta">
                <span>{formatTime(m.createdAt)}</span>
                {own && !peer.self && <MessageStatus message={m} />}
                {own && peer.self && m.pending && <MessageStatus message={m} />}
              </div>
              {m.failed && <div className="send-error">Не отправлено: {m.error}</div>}
            </div>
          );
        })}
      </div>
      <form className="composer" onSubmit={submit}>
        <textarea
          value={text}
          maxLength={MAX_LENGTH}
          placeholder="Сообщение…"
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              submit(e);
            }
          }}
        />
        <button type="submit">Отправить</button>
      </form>
    </section>
  );
}
