import type { CityList } from "@/lib/types";
import type { SearchValues } from "@/lib/search";

export function SearchFilters({
  values,
  form,
}: {
  values: SearchValues;
  form?: string;
}) {
  return (
    <div className="filter-fields search-fields">
      <label className="field">
        Цена за ночь, от
        <input
          form={form}
          name="priceMin"
          inputMode="decimal"
          defaultValue={values.priceMin}
          placeholder="0 ₽"
        />
      </label>
      <label className="field">
        Цена за ночь, до
        <input
          form={form}
          name="priceMax"
          inputMode="decimal"
          defaultValue={values.priceMax}
          placeholder="Без ограничения"
        />
      </label>
      <label className="field">
        Сортировка
        <select
          form={form}
          name="sort"
          defaultValue={values.sort || "priceAsc"}
        >
          <option value="priceAsc">Сначала дешевле</option>
          <option value="priceDesc">Сначала дороже</option>
          <option value="idAsc">По порядку</option>
        </select>
      </label>
    </div>
  );
}

export function SearchForm({
  values,
  cities,
  action = "/demo/hotels",
  showCity = true,
  showFilters = true,
  id,
}: {
  values: SearchValues;
  cities?: CityList;
  action?: string;
  showCity?: boolean;
  showFilters?: boolean;
  id?: string;
}) {
  return (
    <form id={id} className="search-form" action={action} method="get">
      <div className="search-fields">
        {showCity && (
          <label className="field">
            Куда едем
            <select name="city" defaultValue={values.city || ""}>
              <option value="">Все города</option>
              {values.city &&
                !cities?.items.some((c) => c.code === values.city) && (
                  <option value={values.city}>{values.city}</option>
                )}
              {cities?.items.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
        )}
        <label className="field">
          Заезд
          <input
            type="date"
            name="checkInDate"
            defaultValue={values.checkInDate}
          />
        </label>
        <label className="field">
          Выезд
          <input
            type="date"
            name="checkOutDate"
            defaultValue={values.checkOutDate}
          />
        </label>
        <label className="field">
          Гостей
          <input
            type="number"
            name="guests"
            min="1"
            max="100"
            defaultValue={values.guests || "1"}
          />
        </label>
        <button className="button" type="submit">
          Найти
        </button>
      </div>
      {showFilters && (
        <details
          className="search-more"
          open={!!(values.priceMin || values.priceMax)}
        >
          <summary>Цена и сортировка</summary>
          <SearchFilters values={values} />
        </details>
      )}
    </form>
  );
}
