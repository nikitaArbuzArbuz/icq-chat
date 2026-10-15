import { useLayoutEffect, useRef, useState } from 'react';
import Avatar from './Avatar';
import { BackIcon, LogoMark, SendIcon } from './Icons';
import MessageList from './MessageList';

const MAX_LENGTH = 4000;
// Текст без единого видимого символа: обычные и неразрывные пробелы, пробелы нулевой ширины
// и другие форматирующие символы, хангыльские заполнители, пустой символ Брайля, селекторы начертания.
// Та же проверка есть на сервере (Texts.isVisuallyBlank), здесь она нужна, чтобы не отправлять заведомо пустое.
const VISUALLY_BLANK = /^[\s\p{Cc}\p{Cf}͏ᅟᅠㅤﾠ⠀︀-️\u{1D159}\u{E0100}-\u{E01EF}]*$/u;
// Поле ввода растёт вместе с текстом до этой высоты, дальше в нём появляется прокрутка.
const MAX_COMPOSER_HEIGHT = 160;

export default function ChatWindow({ me, peer, chat, onSend, onLoadOlder, onBack }) {
  const [text, setText] = useState('');
  const textareaRef = useRef(null);
  const blank = VISUALLY_BLANK.test(text.trim());

  useLayoutEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) {
      return;
    }
    textarea.style.height = 'auto';
    // scrollHeight не включает рамку, а высота с box-sizing: border-box включает: добавляем её.
    const border = textarea.offsetHeight - textarea.clientHeight;
    textarea.style.height = `${Math.min(textarea.scrollHeight + border, MAX_COMPOSER_HEIGHT)}px`;
  }, [text, peer]);

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
    return (
      <section className="chat chat-empty">
        <div className="empty-state">
          <LogoMark className="empty-mark" />
          <p className="empty-title">Выберите собеседника слева</p>
          <p className="empty-hint">Enter отправляет сообщение, Shift+Enter переносит строку</p>
        </div>
      </section>
    );
  }

  const online = !peer.self && peer.online;
  return (
    <section className="chat">
      {/* Новые key при смене собеседника: шапка и лента создаются заново и плавно появляются. */}
      <header className="chat-header" key={`header-${peer.id}`}>
        <button type="button" className="icon-button back-button" onClick={onBack} aria-label="К списку контактов">
          <BackIcon />
        </button>
        <Avatar login={peer.login} self={peer.self} presence={online ? 'online' : undefined} />
        <div className="chat-title">
          <span className="chat-name">{peer.self ? 'Избранное' : peer.login}</span>
          <span className={`chat-presence${online ? ' online' : ''}`}>
            {peer.self ? 'заметки для себя' : online ? 'в сети' : 'не в сети'}
          </span>
        </div>
      </header>
      <MessageList key={`messages-${peer.id}`} me={me} peer={peer} chat={chat} onLoadOlder={onLoadOlder} />
      <form className="composer" onSubmit={submit}>
        <textarea
          ref={textareaRef}
          rows={1}
          value={text}
          maxLength={MAX_LENGTH}
          placeholder="Сообщение…"
          aria-label="Сообщение"
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              submit(e);
            }
          }}
        />
        <button type="submit" className="send-button" disabled={blank} aria-label="Отправить" title="Отправить">
          <SendIcon />
        </button>
      </form>
    </section>
  );
}
