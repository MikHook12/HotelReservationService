import Link from "next/link";
import type { Booking } from "@/lib/types";
import { rubles } from "@/lib/format";

type BookingSummaryProps = {
  booking: Booking;
  href?: string;
  compact?: boolean;
};

function formatDate(value: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return "Дата не сохранена";
  const date = new Date(`${value}T12:00:00Z`);
  if (
    !Number.isFinite(date.getTime()) ||
    date.toISOString().slice(0, 10) !== value
  )
    return "Дата не сохранена";
  return new Intl.DateTimeFormat("ru-RU", {
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone: "UTC",
  }).format(date);
}

export function BookingSummary({
  booking,
  href,
  compact = false,
}: BookingSummaryProps) {
  const statusLabel =
    booking.status === "CONFIRMED" ? "Подтверждена" : "Отменена";
  const hotelName =
    booking.origin === "V2"
      ? booking.snapshot.hotelName
      : booking.currentCatalogDisplay?.hotelName;
  const roomName =
    booking.origin === "V2"
      ? booking.snapshot.roomName
      : booking.currentCatalogDisplay?.roomName;
  const currentCatalogOnly =
    booking.origin === "LEGACY" && booking.currentCatalogDisplay !== null;

  return (
    <article
      className="panel stack booking-summary"
      role={compact ? "listitem" : undefined}
    >
      <header className="hotel-meta">
        <span>Бронь № {booking.id}</span>
        <span className="badge" data-status={booking.status}>
          {statusLabel}
        </span>
        <span className="badge">
          {booking.origin === "V2" ? "Снимок на момент брони" : "Старая запись"}
        </span>
      </header>

      <div>
        <h2 className="booking-summary-title">
          {href ? (
            <Link href={href}>
              {hotelName || "Сведения об отеле не сохранены"}
            </Link>
          ) : (
            hotelName || "Сведения об отеле не сохранены"
          )}
        </h2>
        {roomName ? <p>{roomName}</p> : null}
        {currentCatalogOnly ? (
          <p className="muted">
            Название взято из текущего каталога; историческое название не
            сохранено.
          </p>
        ) : null}
      </div>

      <dl>
        <div>
          <dt>Заезд</dt>
          <dd>{formatDate(booking.checkInDate)}</dd>
        </div>
        <div>
          <dt>Выезд</dt>
          <dd>{formatDate(booking.checkOutDate)}</dd>
        </div>
        <div>
          <dt>Гости</dt>
          <dd>
            {booking.origin === "V2"
              ? booking.guests
              : "Число гостей не сохранено"}
          </dd>
        </div>
        {!compact && booking.origin === "V2" ? (
          <div>
            <dt>Адрес на момент брони</dt>
            <dd>
              {booking.snapshot.hotelAddress}, {booking.snapshot.cityName}
            </dd>
          </div>
        ) : null}
        {!compact && booking.origin === "V2" ? (
          <div>
            <dt>Часовой пояс</dt>
            <dd>{booking.snapshot.timeZone}</dd>
          </div>
        ) : !compact &&
          booking.origin === "LEGACY" &&
          booking.cancellation.timeZone ? (
          <div>
            <dt>Часовой пояс отмены</dt>
            <dd>{booking.cancellation.timeZone} · уточнён для этой записи</dd>
          </div>
        ) : null}
      </dl>

      <div className="booking-summary-price">
        {booking.pricing ? (
          <>
            <p className="muted">
              {rubles(booking.pricing.nightlyPrice)} за ночь ×{" "}
              {booking.pricing.nights} ноч.
            </p>
            <p className="price">{rubles(booking.pricing.totalPrice)}</p>
          </>
        ) : (
          <p className="price">Стоимость не сохранена</p>
        )}
      </div>

      {href ? (
        <div className="form-actions">
          <Link className="button secondary" href={href}>
            Открыть детали <span aria-hidden="true">→</span>
          </Link>
        </div>
      ) : null}
    </article>
  );
}
