import { useMounted, useOneShot } from '../hooks';
import { toggleTheme, useTheme } from '../theme';
import { MoonIcon, SunIcon } from './Icons';

function ThemeIcon({ theme, animate }) {
  const [turned, stop] = useOneShot(animate);
  return (
    <span className={`theme-icon${turned ? ' turned' : ''}`} onAnimationEnd={stop}>
      {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
    </span>
  );
}

/** Кнопка смены темы. В тёмной теме показывает солнце, в светлой — луну. */
export default function ThemeToggle() {
  const theme = useTheme();
  const mounted = useMounted();
  const label = theme === 'dark' ? 'Включить светлую тему' : 'Включить тёмную тему';
  return (
    <button type="button" className="icon-button" onClick={toggleTheme} aria-label={label} title={label}>
      <ThemeIcon key={theme} theme={theme} animate={mounted} />
    </button>
  );
}
