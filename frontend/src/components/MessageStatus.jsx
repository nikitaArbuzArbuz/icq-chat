import { useMounted, useOneShot } from '../hooks';

const LABELS = {
  pending: 'Отправляется',
  sent: 'Отправлено',
  delivered: 'Доставлено',
  read: 'Прочитано',
};

function stateOf(message) {
  if (message.failed) return 'failed';
  if (message.pending) return 'pending';
  if (message.status === 'READ') return 'read';
  if (message.status === 'DELIVERED') return 'delivered';
  return 'sent';
}

function StatusIcon({ state, animate }) {
  const [changed, stop] = useOneShot(animate);
  return (
    <span
      className={`status ${state}${changed ? ' changed' : ''}`}
      // Анимация идёт у вложенных svg и path, событие о её конце всплывает сюда.
      // Бесконечное вращение стрелки часиков такого события не даёт.
      onAnimationEnd={stop}
      role="img"
      aria-label={LABELS[state]}
      title={LABELS[state]}
    >
      <svg viewBox="0 0 18 12" aria-hidden="true">
        {state === 'pending' ? (
          <>
            <circle cx="9" cy="6" r="4.75" />
            <path className="clock-hand" d="M9 6V3.4" />
          </>
        ) : (
          <>
            <path className="tick tick-1" d="M1.75 6.5L4.75 9.5L10.75 2.5" pathLength="1" />
            {state !== 'sent' && <path className="tick tick-2" d="M8.6 8.35L9.75 9.5L15.75 2.5" pathLength="1" />}
          </>
        )}
      </svg>
    </span>
  );
}

/**
 * Статус своего сообщения: часики (отправляется), одна галочка (сохранено), две (доставлено),
 * две цветные (прочитано). При смене статуса значок создаётся заново (key), и CSS-анимация
 * проигрывается для нового состояния. При первой отрисовке, например при загрузке истории, анимации нет.
 */
export default function MessageStatus({ message }) {
  const mounted = useMounted();
  const state = stateOf(message);
  if (state === 'failed') {
    return (
      <span className="status failed" role="img" aria-label="Не отправлено" title={message.error}>
        !
      </span>
    );
  }
  return <StatusIcon key={state} state={state} animate={mounted} />;
}
