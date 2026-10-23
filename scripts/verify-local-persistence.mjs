// Explicit verification against the NEW dedicated persistent local environment only.
// Secrets/cookies remain in ignored owner-only .local files and are never printed.
import assert from "node:assert/strict";
import { readFile, writeFile, stat } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
const repo = fileURLToPath(new URL("../", import.meta.url));
const local = path.join(repo, ".local");
const stateFile = path.join(local, "persistence-check.json");
const reportFile = path.join(repo, "../work/persistent-local-check.json");
const phase = process.argv[2];
assert.ok(
  ["prepare", "resume"].includes(phase),
  "Usage: node scripts/verify-local-persistence.mjs prepare|resume",
);
const credentials = JSON.parse(
  await readFile(path.join(local, "credentials.json"), "utf8"),
);
assert.equal(credentials.environmentId, "hotel-local-app-v1");
const origin = "http://127.0.0.1:3000";
let cookies = new Map();
async function request(method, apiPath, expected, body, extra = {}) {
  const headers = { Accept: "application/json", ...extra };
  if (method === "POST") headers.Origin = origin;
  if (cookies.size)
    headers.Cookie = [...cookies]
      .map(([name, value]) => `${name}=${value}`)
      .join("; ");
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(new URL(apiPath, origin), {
    method,
    headers,
    redirect: "error",
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(20000),
  });
  for (const cookie of res.headers.getSetCookie()) {
    const pair = cookie.split(";")[0],
      at = pair.indexOf("=");
    if (at < 0) continue;
    const name = pair.slice(0, at),
      value = pair.slice(at + 1);
    if (value) cookies.set(name, value);
    else cookies.delete(name);
  }
  const value = res.status === 204 ? null : await res.json();
  assert.equal(
    res.status,
    expected,
    `${method} ${apiPath}: expected ${expected}, got ${res.status} (${value?.code || ""})`,
  );
  return value;
}
async function csrf() {
  return (await request("GET", "/api/v2/auth/csrf", 200)).token;
}
if (phase === "prepare") {
  try {
    await stat(stateFile);
    throw new Error(
      "Existing persistence check: run resume first; no duplicate booking was sent",
    );
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  let token = await csrf();
  const profile = await request(
    "POST",
    "/api/v2/auth/login",
    200,
    { username: credentials.username, password: credentials.password },
    { "X-CSRF-TOKEN": token },
  );
  assert.deepEqual(profile.user.roles, ["USER"]);
  token = await csrf();
  const hotels = await request("GET", "/api/v2/hotels?sort=idAsc", 200);
  assert.equal(hotels.totalElements, 3);
  const cities = await request("GET", "/api/v2/cities", 200);
  assert.deepEqual(cities.items.map((c) => c.code).sort(), [
    "kazan",
    "moscow",
    "saint-petersburg",
  ]);
  const start = new Date();
  start.setUTCDate(start.getUTCDate() + 20);
  const end = new Date(start);
  end.setUTCDate(end.getUTCDate() + 2);
  const dates = {
    checkInDate: start.toISOString().slice(0, 10),
    checkOutDate: end.toISOString().slice(0, 10),
    guests: "2",
  };
  const rooms = await request(
    "GET",
    `/api/v2/hotels/demo-severny-svet/rooms?${new URLSearchParams(dates)}`,
    200,
  );
  const room = rooms.items.find((item) => item.name === "Двухместная");
  assert.ok(room && room.availability === "AVAILABLE");
  const selection = { ...dates, guests: 2, roomId: room.id };
  const key = randomUUID();
  const quote = await request(
    "POST",
    "/api/v2/bookings/quote",
    201,
    selection,
    { "X-CSRF-TOKEN": token, "Idempotency-Key": randomUUID() },
  );
  const receipt = await request(
    "POST",
    "/api/v2/bookings",
    201,
    { ...selection, quoteToken: quote.quoteToken },
    { "X-CSRF-TOKEN": token, "Idempotency-Key": key },
  );
  assert.equal(receipt.pricing.totalPrice, "10000.00");
  await writeFile(
    stateFile,
    JSON.stringify(
      {
        preparedAt: new Date().toISOString(),
        ownerId: profile.user.id,
        cookies: [...cookies],
        hotels: hotels.items.map((h) => ({
          id: h.id,
          slug: h.slug,
          name: h.name,
        })),
        key,
        receipt,
      },
      null,
      2,
    ),
    { flag: "wx", mode: 0o600 },
  );
  console.log(
    "PASS: dedicated catalogue, generated USER login and confirmed booking prepared. Stop/start ./dev, then run resume.",
  );
} else {
  const before = JSON.parse(await readFile(stateFile, "utf8"));
  cookies = new Map(before.cookies);
  const profile = await request("GET", "/api/v2/auth/me", 200);
  assert.equal(
    profile.user.id,
    before.ownerId,
    "Existing JDBC session must survive a complete restart",
  );
  const hotels = await request("GET", "/api/v2/hotels?sort=idAsc", 200);
  assert.deepEqual(
    hotels.items.map((h) => ({ id: h.id, slug: h.slug, name: h.name })),
    before.hotels,
    "Restart must not recreate or change seeded hotels",
  );
  const booking = await request(
    "GET",
    `/api/v2/me/bookings/${before.receipt.bookingId}`,
    200,
  );
  assert.equal(booking.status, "CONFIRMED");
  assert.equal(booking.pricing.totalPrice, before.receipt.pricing.totalPrice);
  const result = await request(
    "GET",
    "/api/v2/me/booking-operations/result",
    200,
    undefined,
    { "Idempotency-Key": before.key },
  );
  assert.equal(result.state, "SUCCEEDED");
  assert.deepEqual(result.receipt, before.receipt);
  const token = await csrf();
  const replay = await request("POST", "/api/v2/bookings", 201, undefined, {
    "X-CSRF-TOKEN": token,
    "Idempotency-Key": before.key,
  });
  assert.deepEqual(replay, before.receipt);
  const own = await request("GET", "/api/v2/me/bookings", 200);
  assert.equal(own.items.filter((item) => item.id === booking.id).length, 1);
  await request("POST", "/api/v2/auth/logout", 204, undefined, {
    "X-CSRF-TOKEN": token,
  });
  const loginToken = await csrf();
  const relogin = await request(
    "POST",
    "/api/v2/auth/login",
    200,
    { username: credentials.username, password: credentials.password },
    { "X-CSRF-TOKEN": loginToken },
  );
  assert.equal(relogin.user.id, before.ownerId);
  const report = {
    completedAt: new Date().toISOString(),
    preparedAt: before.preparedAt,
    bookingId: booking.id,
    persistent: [
      "catalogue identifiers",
      "ordinary user credentials",
      "JDBC browser session",
      "booking snapshot",
      "idempotent operation result",
      "no duplicate booking on replay",
    ],
    backend: "dedicated Compose, stopped and restarted with named volumes",
  };
  await writeFile(reportFile, JSON.stringify(report, null, 2) + "\n");
  console.log(
    "PASS: catalogue, credentials, JDBC session, booking and operation result survived stop/start; replay created no duplicate.",
  );
}
