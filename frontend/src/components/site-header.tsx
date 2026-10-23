import Link from "next/link";

export function SiteHeader() {
  return (
    <header className="site-header wrap">
      <Link className="brand" href="/" aria-label="Hotel Reservation — главная">
        <span className="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 40 40" focusable="false">
            <rect width="40" height="40" rx="11" />
            <path d="M10 28V12h4v6h12v-6h4v16h-4v-6H14v6z" />
            <path className="brand-mark-accent" d="M17 12h6v3h-6z" />
          </svg>
        </span>
        <span className="brand-name">
          Hotel <span>Reservation</span>
        </span>
      </Link>
      <nav className="nav" aria-label="Основная навигация">
        <Link href="/demo/hotels">Отели</Link>
        <Link href="/account/bookings">Мои бронирования</Link>
        <Link href="/admin">Управление</Link>
        <Link className="nav-login" href="/login">
          Войти
        </Link>
      </nav>
    </header>
  );
}
