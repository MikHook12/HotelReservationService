import Link from "next/link";
import { notFound } from "next/navigation";
import { publicGet, BackendError } from "@/lib/server-api";
import { parseSearch } from "@/lib/search";
import { rubles } from "@/lib/format";
import { reviewHref } from "@/lib/booking-selection";
import type { HotelDetail, RoomPage } from "@/lib/types";
import { PropertyPhoto } from "@/components/property-photo";
import { PageIntro } from "@/components/page-intro";
import { Feedback } from "@/components/feedback";
import { SearchForm } from "../../search-form";
import { Pagination } from "../../pagination";
export const dynamic = "force-dynamic";
export default async function HotelPage({
  params,
  searchParams,
}: {
  params: Promise<{ slug: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { slug } = await params;
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(slug)) notFound();
  const search = parseSearch(await searchParams);
  const path = `/demo/hotels/${slug}`;
  let hotel: HotelDetail;
  try {
    hotel = await publicGet<HotelDetail>(`/hotels/${slug}`);
  } catch (e) {
    if (e instanceof BackendError && e.status === 404) notFound();
    return (
      <div className="wrap">
        <Feedback title="Отель временно недоступен" tone="error">
          <Link className="button secondary" href={path}>
            Повторить
          </Link>
        </Feedback>
      </div>
    );
  }
  const roomQuery = new URLSearchParams(search.query);
  roomQuery.delete("city");
  let rooms: RoomPage | undefined;
  let problem = search.error;
  if (!problem)
    try {
      rooms = await publicGet<RoomPage>(`/hotels/${slug}/rooms?${roomQuery}`);
    } catch (e) {
      problem =
        e instanceof BackendError && e.status === 400
          ? "Проверьте даты и фильтры поиска."
          : "Не удалось загрузить комнаты. Попробуйте ещё раз позже.";
    }
  return (
    <div className="wrap stack">
      <Link className="breadcrumb" href={`/demo/hotels?${search.query}`}>
        ← Все отели
      </Link>
      <PageIntro
        eyebrow={hotel.cityName}
        title={hotel.name}
        description={hotel.description}
      />
      <div className="hotel-detail-art">
        <PropertyPhoto
          photos={hotel.photos}
          className="hotel-art detail-photo"
        />
        <div className="panel stack">
          <span className="badge">Вымышленный отель</span>
          <h2>Об отеле</h2>
          <p>{hotel.address}</p>
          <p className="muted">
            Часовой пояс: {hotel.timeZone}. Правила дат и отмены рассчитываются
            по времени отеля.
          </p>
          <small>
            Фото интерьера показано для примера, а отель является вымышленным.
          </small>
        </div>
      </div>
      <div id="room-search">
        <SearchForm values={search.values} action={path} showCity={false} />
      </div>
      <h2>Выберите номер</h2>
      {problem ? (
        <Feedback title="Поиск комнат не выполнен" tone="error">
          <p>{problem}</p>
          <Link className="button secondary" href={`${path}?${search.query}`}>
            Повторить
          </Link>
        </Feedback>
      ) : (
        rooms && (
          <>
            {rooms.items.length ? (
              <div className="room-grid">
                {rooms.items.map((room) => (
                  <article className="room-card panel stack" key={room.id}>
                    <PropertyPhoto photos={room.photos} fallback="room" />
                    <p className="eyebrow">До {room.capacity} гостей</p>
                    <h3>{room.name}</h3>
                    <p>{room.description}</p>
                    <p className="price">
                      {rubles(room.pricing.nightlyPrice)} <small>за ночь</small>
                    </p>
                    {room.availability !== "NOT_EVALUATED" && (
                      <p>
                        За {room.pricing.nights} ночи:{" "}
                        <strong>{rubles(room.pricing.totalPrice)}</strong>
                      </p>
                    )}
                    <p className="badge">
                      {room.availability === "AVAILABLE"
                        ? "Доступна на выбранные даты"
                        : room.availability === "UNAVAILABLE"
                          ? "Занята на выбранные даты"
                          : "Выберите даты для проверки доступности"}
                    </p>
                    <small className="muted">
                      Доступность может измениться до подтверждения брони.
                    </small>
                    {room.availability === "AVAILABLE" &&
                      search.values.checkInDate &&
                      search.values.checkOutDate && (
                        <Link
                          className="button"
                          href={reviewHref({
                            roomId: room.id,
                            checkInDate: search.values.checkInDate,
                            checkOutDate: search.values.checkOutDate,
                            guests: Number(search.values.guests || 1),
                          })}
                        >
                          Выбрать комнату
                        </Link>
                      )}
                    {room.availability === "NOT_EVALUATED" && (
                      <a className="button secondary" href="#room-search">
                        Указать даты
                      </a>
                    )}
                  </article>
                ))}
              </div>
            ) : (
              <Feedback title="Подходящих комнат нет">
                <p>Попробуйте другие даты или количество гостей.</p>
              </Feedback>
            )}
            <Pagination
              path={path}
              values={search.values}
              page={rooms.page}
              totalPages={rooms.totalPages}
            />
          </>
        )
      )}
      <section className="panel stack">
        <h2>Условная локация</h2>
        <p>{hotel.address}</p>
        <p className="muted">
          Это вымышленный объект, а не место реального размещения. Карта и
          геолокация здесь не загружаются.
        </p>
      </section>
    </div>
  );
}
