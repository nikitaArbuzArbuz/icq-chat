const DAY_MS = 24 * 60 * 60 * 1000;

function startOfDay(value) {
  const date = new Date(value);
  date.setHours(0, 0, 0, 0);
  return date;
}

/** Сколько календарных дней прошло: 0 — сегодня, 1 — вчера. Math.round сглаживает переход на летнее время. */
function daysAgo(value) {
  return Math.round((startOfDay(Date.now()) - startOfDay(value)) / DAY_MS);
}

function isThisYear(date) {
  return date.getFullYear() === new Date().getFullYear();
}

/** Ключ календарного дня: по нему сообщения делятся на дни. */
export function dayKey(value) {
  return startOfDay(value).getTime();
}

/** Время сообщения: «18:49». */
export function formatTime(value) {
  if (!value) {
    return '';
  }
  return new Date(value).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
}

/** Подпись над сообщениями одного дня: «Сегодня», «Вчера», «12 октября», «12 октября 2025 г.». */
export function formatDay(value) {
  const days = daysAgo(value);
  if (days <= 0) {
    return 'Сегодня';
  }
  if (days === 1) {
    return 'Вчера';
  }
  const date = new Date(value);
  return date.toLocaleDateString('ru-RU', isThisYear(date)
    ? { day: 'numeric', month: 'long' }
    : { day: 'numeric', month: 'long', year: 'numeric' });
}

/** Время последнего сообщения в списке контактов: «18:49», «вчера», «пн», «12 окт», «12.10.25». */
export function formatContactTime(value) {
  const days = daysAgo(value);
  if (days <= 0) {
    return formatTime(value);
  }
  if (days === 1) {
    return 'вчера';
  }
  const date = new Date(value);
  if (days < 7) {
    return date.toLocaleDateString('ru-RU', { weekday: 'short' });
  }
  if (isThisYear(date)) {
    return date.toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' }).replace('.', '');
  }
  return date.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: '2-digit' });
}
