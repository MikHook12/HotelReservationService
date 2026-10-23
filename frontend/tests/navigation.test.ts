import assert from "node:assert/strict";
import test from "node:test";
import { safeReturnTo } from "../src/lib/navigation";

test("allows catalogue routes and a restricted, stable filter query", () => {
  assert.equal(safeReturnTo("/demo/hotels"), "/demo/hotels");
  assert.equal(
    safeReturnTo("/demo/hotels/sea-view?city=sochi&guests=2&page=1"),
    "/demo/hotels/sea-view?city=sochi&guests=2&page=1",
  );
  assert.equal(safeReturnTo("/account/bookings"), "/account/bookings");
});

test("rejects external, encoded, malformed, and unrelated destinations", () => {
  for (const value of [
    "https://evil.example",
    "//evil.example",
    "/\\evil.example",
    "/demo/hotels/%2fadmin",
    "/demo/hotels/../account",
    "/demo//hotels",
    "/demo/hotels/",
    "/login",
    "/api/v2/auth/me",
    "/account/bookings?page=1",
    "/demo/hotels?next=https://evil.example",
    "/demo/hotels?city=a&city=b",
    "/demo/hotels?city=a+b",
    "/demo/hotels#fragment",
    "/demo/hotels\n",
  ])
    assert.equal(
      safeReturnTo(value),
      "/demo/hotels",
      `unsafe return path: ${JSON.stringify(value)}`,
    );
});

test("uses the catalogue fallback for absent or oversized values", () => {
  assert.equal(safeReturnTo(null), "/demo/hotels");
  assert.equal(
    safeReturnTo("/demo/hotels/" + "a".repeat(2049)),
    "/demo/hotels",
  );
});

test("allows only complete booking review selections and own booking details", () => {
  const review =
    "/demo/booking/review?roomId=42&checkInDate=2026-10-25&checkOutDate=2026-10-28&guests=2";
  assert.equal(safeReturnTo(review), review);
  assert.equal(safeReturnTo("/account/bookings/123"), "/account/bookings/123");
  for (const value of [
    "/demo/booking/review?roomId=42&checkInDate=2026-10-25&checkOutDate=2026-10-28",
    "/demo/booking/review?roomId=42&roomId=43&checkInDate=2026-10-25&checkOutDate=2026-10-28&guests=2",
    "/demo/booking/review?roomId=42&checkInDate=2026-02-30&checkOutDate=2026-03-02&guests=2",
    "/demo/booking/review?roomId=42&checkInDate=2026-10-28&checkOutDate=2026-10-25&guests=2",
    "/demo/booking/review?roomId=0&checkInDate=2026-10-25&checkOutDate=2026-10-28&guests=2",
    "/demo/booking/review?roomId=42&checkInDate=2026-10-25&checkOutDate=2026-10-28&guests=2&key=secret",
    "/account/bookings/0",
    "/account/bookings/123?token=secret",
  ])
    assert.equal(safeReturnTo(value), "/demo/hotels");
});
