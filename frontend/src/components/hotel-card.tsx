import Link from "next/link";
import { PropertyPhoto } from "./property-photo";
import { nightsLabel } from "@/lib/format";
import type { HotelCard as HotelCardData } from "@/lib/types";

type HotelCardProps = {
  hotel: HotelCardData;
  href: string;
};

const MONEY_PATTERN = /^(0|[1-9][0-9]{0,11})\.[0-9]{2}$/;

function formatMoney(amount: string, currency: string): string | null {
  if (!MONEY_PATTERN.test(amount) || !/^[A-Z]{3}$/.test(currency)) return null;

  try {
    const [whole, fraction] = amount.split(".");
    const groupedWhole = new Intl.NumberFormat("ru-RU").format(BigInt(whole));
    const currencyPart = new Intl.NumberFormat("ru-RU", {
      style: "currency",
      currency,
      minimumFractionDigits: 0,
      maximumFractionDigits: 0,
    })
      .formatToParts(0)
      .find((part) => part.type === "currency")?.value;

    if (!currencyPart) return null;
    const decimals = fraction === "00" ? "" : `,${fraction}`;
    return `${groupedWhole}${decimals}\u00a0${currencyPart}`;
  } catch {
    return null;
  }
}

function pluralRooms(count: number): string {
  const remainder100 = count % 100;
  const remainder10 = count % 10;
  if (remainder100 >= 11 && remainder100 <= 14) return "вариантов комнат";
  if (remainder10 === 1) return "вариант комнаты";
  if (remainder10 >= 2 && remainder10 <= 4) return "варианта комнат";
  return "вариантов комнат";
}

export function HotelCard({ hotel, href }: HotelCardProps) {
  const nightlyPrice = formatMoney(
    hotel.fromPrice.nightlyPrice,
    hotel.fromPrice.currency,
  );
  const totalPrice = formatMoney(
    hotel.fromPrice.totalPrice,
    hotel.fromPrice.currency,
  );
  const hasPrice = nightlyPrice !== null && totalPrice !== null;

  return (
    <article className="hotel-card panel">
      <Link
        className="hotel-art-link"
        href={href}
        aria-label={`Подробнее: ${hotel.name}`}
      >
        <PropertyPhoto
          photos={hotel.photos}
          fallback={Number(hotel.id) % 2 ? "hotel" : "room"}
        />
      </Link>

      <div className="hotel-copy">
        <div className="hotel-meta">
          <span>{hotel.cityName}</span>
          {hotel.isDemo ? <span className="badge">Демо-предложение</span> : null}
        </div>
        <h2>
          <Link href={href}>{hotel.name}</Link>
        </h2>

        <div className="hotel-card-bottom">
          <div className="price-block">
            {hasPrice ? (
              <>
                <p className="price">
                  <span>{hotel.isDemo ? "Учебная цена от " : "от "}</span>
                  {nightlyPrice}
                  <small> / ночь</small>
                </p>
                {hotel.fromPrice.nights > 1 ? (
                  <p className="muted price-total">
                    {totalPrice} за {nightsLabel(hotel.fromPrice.nights)}
                  </p>
                ) : null}
              </>
            ) : (
              <p className="muted">Цена сейчас недоступна</p>
            )}
          </div>
          <Link className="button secondary hotel-card-action" href={href}>
            Выбрать номер
          </Link>
        </div>

        <div className="hotel-availability" aria-live="polite">
          {hotel.availabilityEvaluated ? (
            <span className="badge availability-evaluated">
              Подходит: {hotel.matchedRoomCount}{" "}
              {pluralRooms(hotel.matchedRoomCount)}
            </span>
          ) : (
            <span className="muted">
              Укажите даты, чтобы проверить свободные номера
            </span>
          )}
        </div>
      </div>
    </article>
  );
}
