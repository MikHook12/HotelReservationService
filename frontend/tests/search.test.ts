import { test } from "node:test";
import assert from "node:assert/strict";
import { parseSearch, hotelHref, pageHref } from "../src/lib/search";
import { rubles } from "../src/lib/format";
test("calendar validation rejects impossible dates, reversed and overlong stays", () => {
  for (const dates of [
    ["2026-02-30", "2026-03-02"],
    ["2026-11-12", "2026-11-10"],
    ["2026-11-01", "2026-12-02"],
  ])
    assert.ok(
      parseSearch({ checkInDate: dates[0], checkOutDate: dates[1] }).error,
    );
  assert.equal(
    parseSearch({ checkInDate: "2028-02-29", checkOutDate: "2028-03-01" })
      .error,
    null,
  );
});
test("duplicates, unknown keys and incomplete date pairs do not reach search", () => {
  for (const q of [
    { guests: ["1", "2"] },
    { token: "secret" },
    { checkInDate: "2026-11-10" },
    { priceMin: "4.99", priceMax: "3.00" },
    { guests: "1.5" },
  ])
    assert.ok(parseSearch(q).error);
});
test("hotel and pagination links retain selection and reset irrelevant pagination", () => {
  const q = {
    city: "voronezh",
    guests: "2",
    page: "3",
    checkInDate: "2026-11-10",
    checkOutDate: "2026-11-12",
  };
  const url = new URL(hotelHref("pine-house", q), "https://example.test");
  assert.equal(url.searchParams.get("guests"), "2");
  assert.equal(url.searchParams.get("city"), "voronezh");
  assert.equal(url.searchParams.get("checkInDate"), q.checkInDate);
  assert.equal(url.searchParams.get("checkOutDate"), q.checkOutDate);
  assert.equal(url.searchParams.has("page"), false);
  assert.equal(
    new URL(pageHref("/demo/hotels", q, 4), url).searchParams.get("city"),
    "voronezh",
  );
});
test("money formatting preserves exact decimal strings", () => {
  assert.equal(rubles("9999999999.99"), "9 999 999 999,99 ₽");
  assert.equal(rubles("400.01"), "400,01 ₽");
  assert.equal(rubles("NaN"), "Цена уточняется");
});

test("price filters turn human ruble input into exact API decimals without rounding", () => {
  const parsed = parseSearch({ priceMin: "1 000,5", priceMax: "5000" });
  assert.equal(parsed.error, null);
  assert.equal(parsed.query.get("priceMin"), "1000.50");
  assert.equal(parsed.query.get("priceMax"), "5000.00");
  assert.equal(
    parseSearch({ priceMax: "0005.01" }).query.get("priceMax"),
    "5.01",
  );
  for (const value of ["1.005", "1e3", "-1", "10000000000"]) {
    assert.ok(parseSearch({ priceMax: value }).error);
  }
});
