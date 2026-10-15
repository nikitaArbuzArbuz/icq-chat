// Иконки интерфейса. Цвет берут из currentColor, поэтому сами подстраиваются под тему.

/** Знак приложения: облачко сообщения с двумя галочками, как у доставленного сообщения. */
export function LogoMark({ className = '' }) {
  return (
    <svg className={`logo-mark ${className}`} viewBox="0 0 48 48" aria-hidden="true">
      <path
        className="logo-bubble"
        d="M16 5H32A12 12 0 0 1 44 17V25A12 12 0 0 1 32 37H20L9 44.5Q10.5 40 10.8 35.6A12 12 0 0 1 4 25V17A12 12 0 0 1 16 5Z"
      />
      <path className="logo-tick logo-tick-1" d="M13 21.5L17.5 26L27 15.5" pathLength="1" />
      <path className="logo-tick logo-tick-2" d="M23.2 25.2L24 26L33.5 15.5" pathLength="1" />
    </svg>
  );
}

function Icon({ children, strokeWidth = 1.8 }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  );
}

export function SearchIcon() {
  return (
    <Icon>
      <circle cx="11" cy="11" r="6.5" />
      <path d="M16 16l4 4" />
    </Icon>
  );
}

export function SendIcon() {
  return (
    <Icon strokeWidth={2.2}>
      <path d="M12 19V5.5M6.5 11L12 5.5L17.5 11" />
    </Icon>
  );
}

export function LogoutIcon() {
  return (
    <Icon>
      <path d="M14 4h3.5A2.5 2.5 0 0 1 20 6.5v11a2.5 2.5 0 0 1-2.5 2.5H14" />
      <path d="M10 8l-4 4 4 4M6 12h10" />
    </Icon>
  );
}

export function BackIcon() {
  return (
    <Icon strokeWidth={2}>
      <path d="M15 5l-7 7 7 7" />
    </Icon>
  );
}

export function SunIcon() {
  return (
    <Icon>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2.5v2M12 19.5v2M4.6 4.6L6 6M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4L6 18M18 6l1.4-1.4" />
    </Icon>
  );
}

export function MoonIcon() {
  return (
    <Icon>
      <path d="M20 14.2A8 8 0 1 1 9.8 4a6.5 6.5 0 0 0 10.2 10.2z" />
    </Icon>
  );
}

export function BookmarkIcon() {
  return (
    <Icon>
      <path d="M7 4.5h10a1 1 0 0 1 1 1V20l-6-4-6 4V5.5a1 1 0 0 1 1-1z" />
    </Icon>
  );
}
