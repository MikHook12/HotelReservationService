import Link from "next/link";
export function SiteFooter() {
  return (
    <footer className="site-footer wrap">
      <div>
        <Link className="footer-brand" href="/">
          Hotel Reservation
        </Link>
        <p>Поиск отелей и управление бронированиями.</p>
      </div>
      <nav className="footer-note" aria-label="Ссылки в подвале">
        <Link href="/demo/hotels">Каталог отелей</Link>
        <Link href="/account/bookings">Мои бронирования</Link>
        <span>Учебный сервис · без оплаты</span>
      </nav>
    </footer>
  );
}
