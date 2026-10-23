import Link from "next/link";
import type { Metadata } from "next";
import type { HotelPage, CityList } from "@/lib/types";
import { publicGet, BackendError } from "@/lib/server-api";
import { parseSearch, hotelHref } from "@/lib/search";
import { Feedback } from "@/components/feedback";
import { HotelCard } from "@/components/hotel-card";
import { SearchForm, SearchFilters } from "../search-form";
import { Pagination } from "../pagination";
export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Поиск отелей" };
export default async function HotelsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { values, query, error } = parseSearch(await searchParams);
  let problem = error;
  const [cities, hotels] = await Promise.all([
    publicGet<CityList>("/cities").catch(() => undefined),
    error
      ? Promise.resolve(undefined)
      : publicGet<HotelPage>(`/hotels?${query}`).catch((e) => {
          problem =
            e instanceof BackendError && e.status === 400
              ? "Проверьте даты и фильтры поиска."
              : "Не удалось загрузить отели. Попробуйте ещё раз позже.";
          return undefined;
        }),
  ]);
  const city = cities?.items.find((item) => item.code === values.city);
  return (
    <div className="catalogue-page wrap stack">
      <header className="catalogue-intro">
        <h1>{city ? `Отели: ${city.name}` : "Найдите подходящий отель"}</h1>
        <p>Выберите даты поездки — покажем свободные номера и стоимость.</p>
      </header>
      <SearchForm
        id="catalogue-search"
        values={values}
        cities={cities}
        showFilters={false}
      />
      <div className="results-layout">
        <aside className="catalogue-sidebar panel" aria-label="Фильтры отелей">
          <h2>Уточнить поиск</h2>
          <SearchFilters values={values} form="catalogue-search" />
          <button className="button" form="catalogue-search" type="submit">
            Показать отели
          </button>
          <Link href="/demo/hotels">Сбросить фильтры</Link>
          <p className="muted">
            Цена указана за комнату. Точные условия — при выборе номера.
          </p>
        </aside>
        <section className="results-main" aria-label="Результаты поиска">
          {problem ? (
            <Feedback title="Поиск не выполнен" tone="error">
              <p>{problem}</p>
              <Link className="button secondary" href={`/demo/hotels?${query}`}>
                Повторить поиск
              </Link>
            </Feedback>
          ) : (
            hotels && (
              <>
                {hotels.warnings.length > 0 && (
                  <Feedback title="Даты подходят не всем отелям">
                    <p>
                      Часть отелей исключена с учётом их местного времени.
                      Указанные вами даты не изменены.
                    </p>
                  </Feedback>
                )}
                <div className="results-heading">
                  <h2>Доступные варианты</h2>
                  <span className="muted">Найдено: {hotels.totalElements}</span>
                </div>
                {hotels.items.length ? (
                  <div className="hotel-grid">
                    {hotels.items.map((h) => (
                      <HotelCard
                        key={h.id}
                        hotel={h}
                        href={hotelHref(h.slug, values)}
                      />
                    ))}
                  </div>
                ) : (
                  <Feedback title="Подходящих вариантов пока нет">
                    <p>Измените даты, количество гостей или диапазон цены.</p>
                    <Link className="button secondary" href="/demo/hotels">
                      Сбросить фильтры
                    </Link>
                  </Feedback>
                )}
                <Pagination
                  path="/demo/hotels"
                  values={values}
                  page={hotels.page}
                  totalPages={hotels.totalPages}
                />
              </>
            )
          )}
        </section>
      </div>
    </div>
  );
}
