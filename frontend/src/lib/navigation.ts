const FALLBACK = "/demo/hotels";
const CONTROL = /[\u0000-\u001f\u007f]/;
const FORBIDDEN_ESCAPES = /%(?:2f|5c|00|0[0-9a-f]|1[0-9a-f]|7f)/i;

function validQuery(raw: string): boolean {
  if (!raw) return true;
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
  const seen = new Set<string>();
  for (const part of raw.split("&")) {
    if (!part) return false;
    const at = part.indexOf("=");
    const key = at < 0 ? part : part.slice(0, at);
    const value = at < 0 ? "" : part.slice(at + 1);
    if (
      !/^[A-Za-z][A-Za-z0-9]*$/.test(key) ||
      !allowed.has(key) ||
      seen.has(key)
    )
      return false;
    if (!/^[A-Za-z0-9._~-]*$/.test(value)) return false;
    seen.add(key);
  }
  return true;
}

function validBookingReviewQuery(raw: string): boolean {
  const values = new Map<string, string>();
  for (const part of raw.split("&")) {
    const at = part.indexOf("=");
    if (at < 1) return false;
    const key = part.slice(0, at);
    const value = part.slice(at + 1);
    if (
      !["roomId", "checkInDate", "checkOutDate", "guests"].includes(key) ||
      values.has(key)
    )
      return false;
    values.set(key, value);
  }
  if (
    values.size !== 4 ||
    !/^[1-9][0-9]*$/.test(values.get("roomId") ?? "") ||
    !/^[1-9][0-9]*$/.test(values.get("guests") ?? "")
  )
    return false;
  const checkIn = values.get("checkInDate") ?? "";
  const checkOut = values.get("checkOutDate") ?? "";
  const validDate = (date: string) => {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return false;
    const parsed = new Date(`${date}T00:00:00Z`);
    return (
      Number.isFinite(parsed.getTime()) &&
      parsed.toISOString().slice(0, 10) === date
    );
  };
  return validDate(checkIn) && validDate(checkOut) && checkOut > checkIn;
}

/** Accepts only first-party catalogue/account destinations. Never accepts an arbitrary redirect. */
export function safeReturnTo(value: string | null | undefined): string {
  if (
    !value ||
    value.length > 2048 ||
    CONTROL.test(value) ||
    value.includes("\\") ||
    value.includes("#") ||
    value.startsWith("//") ||
    value.includes("://") ||
    (value.includes("%") && FORBIDDEN_ESCAPES.test(value))
  ) {
    return FALLBACK;
  }
  const queryAt = value.indexOf("?");
  const pathname = queryAt < 0 ? value : value.slice(0, queryAt);
  const query = queryAt < 0 ? "" : value.slice(queryAt + 1);
  if (
    !pathname.startsWith("/") ||
    pathname.startsWith("//") ||
    pathname.includes("%")
  )
    return FALLBACK;
  if (
    pathname
      .slice(1)
      .split("/")
      .some((part) => part === "" || part === "." || part === "..")
  )
    return FALLBACK;
  if (
    pathname === "/admin" ||
    pathname === "/admin/hotels/new" ||
    /^\/admin\/hotels\/[1-9][0-9]*$/.test(pathname)
  )
    return query === "" ? pathname : FALLBACK;
  if (
    pathname === "/account/bookings" ||
    /^\/account\/bookings\/[1-9][0-9]*$/.test(pathname)
  )
    return query === "" ? pathname : FALLBACK;
  if (pathname === "/demo/booking/review")
    return validBookingReviewQuery(query) ? value : FALLBACK;
  if (!validQuery(query)) return FALLBACK;
  if (pathname === "/demo/hotels") return value;
  if (/^\/demo\/hotels\/[a-z0-9]+(?:-[a-z0-9]+)*$/.test(pathname)) return value;
  return FALLBACK;
}
