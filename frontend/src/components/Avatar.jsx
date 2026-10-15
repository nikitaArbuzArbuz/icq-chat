import { useMounted, useOneShot } from '../hooks';
import { BookmarkIcon } from './Icons';

// Оттенки фона в OKLCH: при одинаковой светлоте все цвета выглядят одинаково яркими,
// и белые буквы читаются на любом. Жёлтых и салатовых нет, на них белое теряется.
const HUES = [15, 40, 140, 165, 190, 215, 240, 262, 285, 310, 335, 358];

// Хеш FNV-1a: близкие логины («user1», «user2») получают разные цвета. Цвет зависит только от логина,
// поэтому у человека он один и тот же в списке, в шапке диалога и после перезагрузки.
function hueOf(login) {
  let hash = 0x811c9dc5;
  for (const char of login) {
    hash ^= char.codePointAt(0);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return HUES[hash % HUES.length];
}

// «alice» → «A», «kate.morozova» → «KM».
function initialsOf(login) {
  const parts = login.split(/[\s._-]+/).filter(Boolean);
  const first = Array.from(parts[0] ?? login)[0] ?? '?';
  const second = parts.length > 1 ? Array.from(parts[1])[0] : '';
  return (first + second).toUpperCase();
}

function PresenceDot({ presence, animate }) {
  const [appear, stop] = useOneShot(animate);
  return (
    <span
      className={`presence-dot ${presence}${appear ? ' appear' : ''}`}
      // У точки две анимации: «выпрыгивание» и расходящееся кольцо. Кольцо заканчивается последним.
      onAnimationEnd={(e) => e.animationName === 'ring' && stop()}
    />
  );
}

/**
 * Аватарка с инициалами. Для «Избранного» вместо инициалов закладка.
 * presence: 'online' — зелёная точка, 'connecting' — жёлтая мигающая (своё соединение ещё не готово),
 * без значения — точки нет. Точка, которая появилась уже на экране, «выпрыгивает», чтобы это было заметно.
 */
export default function Avatar({ login, self = false, presence, size = 'md' }) {
  const mounted = useMounted();
  return (
    <span
      className={`avatar avatar-${size}${self ? ' avatar-self' : ''}`}
      style={self ? undefined : { '--hue': hueOf(login) }}
      aria-hidden="true"
    >
      {self ? <BookmarkIcon /> : initialsOf(login)}
      {presence && <PresenceDot key={presence} presence={presence} animate={mounted} />}
    </span>
  );
}
