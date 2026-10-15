import { useSyncExternalStore } from 'react';
import { flushSync } from 'react-dom';

// Тема оформления: 'light' или 'dark'. Сама тема — это атрибут data-theme на <html>,
// а цвета для каждой темы заданы переменными в styles.css.
// Первый раз атрибут ставит скрипт в index.html, ещё до загрузки React, поэтому здесь он уже есть.
// Пока пользователь не выбрал тему сам, она следует за настройкой системы.

const STORAGE_KEY = 'icq-chat-theme';
const THEME_COLORS = { light: '#ffffff', dark: '#151b23' };

const systemDark = window.matchMedia('(prefers-color-scheme: dark)');
const listeners = new Set();
let theme = document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';

function storedTheme() {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value === 'light' || value === 'dark' ? value : null;
  } catch {
    return null;
  }
}

function applyTheme(next) {
  theme = next;
  document.documentElement.dataset.theme = next;
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLORS[next]);
  // Компоненты перерисовываются сразу, а не позже: иначе при плавной смене темы иконка переключателя
  // поменялась бы уже после анимации.
  flushSync(() => listeners.forEach((listener) => listener()));
}

systemDark.addEventListener('change', () => {
  if (storedTheme() == null) {
    applyTheme(systemDark.matches ? 'dark' : 'light');
  }
});

export function toggleTheme() {
  const next = theme === 'dark' ? 'light' : 'dark';
  try {
    localStorage.setItem(STORAGE_KEY, next);
  } catch {
    // Без localStorage тема переключится, но не запомнится.
  }
  // View Transitions API плавно переводит страницу из старого вида в новый. Браузеры без него
  // и пользователи, отключившие анимации в системе, получают мгновенное переключение.
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (document.startViewTransition && !reduceMotion) {
    document.startViewTransition(() => applyTheme(next));
  } else {
    applyTheme(next);
  }
}

function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function getTheme() {
  return theme;
}

/** Текущая тема. Компонент перерисовывается, когда она меняется. */
export function useTheme() {
  return useSyncExternalStore(subscribe, getTheme);
}
