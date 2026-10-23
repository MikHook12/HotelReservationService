import { parseSearch } from "./search";
import type { QuoteRequest } from "./types";
export function selectionFromQuery(
  raw: Record<string, string | string[] | undefined>,
): QuoteRequest | null {
  const { roomId, ...rest } = raw;
  if (
    typeof roomId !== "string" ||
    !/^[1-9][0-9]*$/.test(roomId) ||
    Object.keys(rest).some(
      (k) => !["checkInDate", "checkOutDate", "guests"].includes(k),
    )
  )
    return null;
  const parsed = parseSearch(rest);
  const { checkInDate, checkOutDate, guests } = parsed.values;
  if (parsed.error || !checkInDate || !checkOutDate || !guests) return null;
  return { roomId, checkInDate, checkOutDate, guests: Number(guests) };
}
export function reviewHref(selection: QuoteRequest): string {
  return `/demo/booking/review?${new URLSearchParams({ ...selection, guests: String(selection.guests) })}`;
}
