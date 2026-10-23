export type SearchValues = Record<string, string>;
const allowed = new Set([
  "city",
  "checkInDate",
  "checkOutDate",
  "guests",
  "priceMin",
  "priceMax",
  "page",
  "size",
  "sort",
]);
export function parseSearch(
  raw: Record<string, string | string[] | undefined>,
) {
  const values: SearchValues = {};
  let error: string | null = null;
  for (const [key, value] of Object.entries(raw)) {
    if (!allowed.has(key) || Array.isArray(value)) {
      error = "Некорректные или повторяющиеся параметры поиска.";
      continue;
    }
    if (value) values[key] = value;
  }
  const date = (s: string) =>
    /^\d{4}-\d{2}-\d{2}$/.test(s) &&
    !Number.isNaN(Date.parse(s)) &&
    new Date(s).toISOString().slice(0, 10) === s;
  if (!!values.checkInDate !== !!values.checkOutDate)
    error = "Укажите обе даты: заезд и выезд.";
  if ([values.checkInDate, values.checkOutDate].some((v) => v && !date(v)))
    error = "Проверьте календарные даты.";
  if (values.checkInDate && values.checkOutDate) {
    const nights =
      (Date.parse(values.checkOutDate) - Date.parse(values.checkInDate)) /
      86400000;
    if (nights < 1 || nights > 30)
      error = "Продолжительность поездки — от 1 до 30 ночей.";
  }
  for (const [key, min, max] of [
    ["guests", 1, 100],
    ["page", 0, 1000000],
    ["size", 1, 50],
  ] as const)
    if (
      values[key] &&
      (!/^\d+$/.test(values[key]) ||
        Number(values[key]) < min ||
        Number(values[key]) > max)
    )
      error = "Проверьте число гостей и параметры страницы.";
  if (values.city && !/^[a-z0-9-]{1,64}$/.test(values.city))
    error = "Выберите город из списка.";
  for (const key of ["priceMin", "priceMax"]) {
    if (!values[key]) continue;
    const input = values[key]
      .trim()
      .replace(/[ \u00a0\u202f]/g, "")
      .replace(",", ".");
    if (!/^\d{1,10}(?:\.\d{1,2})?$/.test(input)) {
      error = "Укажите цену в рублях с точностью до копеек.";
    } else {
      // The API expects exact Decimal strings; users may enter whole rubles or a Russian decimal comma.
      const [whole, fraction = ""] = input.split(".");
      values[key] = `${BigInt(whole)}.${fraction.padEnd(2, "0")}`;
    }
  }
  if (
    values.priceMin &&
    values.priceMax &&
    Number(values.priceMin) > Number(values.priceMax)
  )
    error = "Минимальная цена не может быть выше максимальной.";
  if (values.sort && !["priceAsc", "priceDesc", "idAsc"].includes(values.sort))
    error = "Выберите доступную сортировку.";
  return { values, query: new URLSearchParams(values), error };
}
export function hotelHref(slug: string, values: SearchValues) {
  const q = new URLSearchParams(values);
  q.delete("city");
  q.delete("page");
  return `/demo/hotels/${encodeURIComponent(slug)}${q.size ? `?${q}` : ""}`;
}
export function pageHref(path: string, values: SearchValues, page: number) {
  const q = new URLSearchParams({ ...values, page: String(page) });
  return `${path}?${q}`;
}
