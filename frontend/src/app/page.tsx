import Link from "next/link";
import { publicGet } from "@/lib/server-api";
import type { CityList } from "@/lib/types";
import { SearchForm } from "./demo/search-form";
export const dynamic = "force-dynamic";

export default async function Home() {
  const cities = await publicGet<CityList>("/cities").catch(() => undefined);
  const moscow = cities?.items.find((city) => city.code === "moscow");
  return (
    <div className="home-page">
      <section className="travel-hero">
        <img src="/photos/hotel-hero.jpg" alt="" fetchPriority="high" />
        <div className="travel-hero-copy wrap">
          <h1>
            Хорошая поездка начинается
            <br />с хорошего отеля
          </h1>
          <p>Найдите место, в котором захочется остаться.</p>
        </div>
      </section>
      <section className="home-search wrap" aria-labelledby="search-title">
        <div className="home-search-panel">
          <h2 id="search-title">Куда отправимся?</h2>
          <SearchForm values={{}} cities={cities} showFilters={false} />
          <div className="search-suggestions">
            <span>Посмотреть:</span>
            {cities?.items.map((city) => (
              <Link
                key={city.code}
                href={`/demo/hotels?city=${encodeURIComponent(city.code)}`}
              >
                {city.name}
              </Link>
            ))}
            <Link href="/demo/hotels">Все отели</Link>
          </div>
        </div>
      </section>
      <div className="home-content wrap">
        <section aria-labelledby="ideas-title">
          <div className="section-heading">
            <h2 id="ideas-title">С чего начнётся ваша поездка?</h2>
            <p>Выберите направление, компанию или подходящую цену.</p>
          </div>
          <div className="destination-grid">
            <Link
              className="destination-card"
              href={moscow ? "/demo/hotels?city=moscow" : "/demo/hotels"}
            >
              <img
                src="/photos/moscow.jpg"
                alt="Городской вид Москвы"
                loading="lazy"
              />
              <div className="destination-copy">
                <h3>{moscow ? "Москва" : "Выбрать направление"}</h3>
                <p>
                  {moscow ? "Отели в столице" : "Посмотреть доступные города"}
                </p>
              </div>
            </Link>
            <Link className="destination-card" href="/demo/hotels?guests=2">
              <img
                src="/photos/room.jpg"
                alt="Пример интерьера номера"
                loading="lazy"
              />
              <div className="destination-copy">
                <h3>Поездка вдвоём</h3>
                <p>Комнаты для двух гостей</p>
              </div>
            </Link>
            <Link
              className="destination-card"
              href="/demo/hotels?priceMax=5000"
            >
              <img
                src="/photos/hotel-hero.jpg"
                alt="Пример гостиничного интерьера"
                loading="lazy"
              />
              <div className="destination-copy">
                <h3>До 5 000 ₽ за ночь</h3>
                <p>Варианты в вашем бюджете</p>
              </div>
            </Link>
          </div>
          <p className="photo-note">
            Фотографии интерьеров используются для оформления и не изображают
            номера демо-отелей.
          </p>
        </section>
        <section className="how-it-works" aria-label="Как забронировать">
          <div>
            <span aria-hidden="true">01</span>
            <h3>Найдите отель</h3>
            <p>Укажите город, даты и количество гостей.</p>
          </div>
          <div>
            <span aria-hidden="true">02</span>
            <h3>Выберите номер</h3>
            <p>Сравните цены и ознакомьтесь с условиями.</p>
          </div>
          <div>
            <span aria-hidden="true">03</span>
            <h3>Управляйте бронью</h3>
            <p>Детали и отмена доступны в личном кабинете.</p>
          </div>
        </section>
      </div>
    </div>
  );
}
