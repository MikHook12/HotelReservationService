// HTTP/HTTPS protocol acceptance against the real disposable Spring/Next environment.
// HTTPS uses a dedicated certificate; local HTTP is selected by the preview origin.
import https from "node:https";
import http from "node:http";
import { readFile, writeFile } from "node:fs/promises";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
const [readyPath, caPath, reportPath] = process.argv.slice(2);
if (!readyPath || !caPath || !reportPath)
  throw new Error(
    "Usage: node scripts/verify-live.mjs READY_JSON CERT_PEM_OR_DASH REPORT_JSON",
  );
const ready = JSON.parse(await readFile(readyPath, "utf8"));
const origin = process.env.PUBLIC_ORIGIN || "http://127.0.0.1:3000";
const tls = new URL(origin).protocol === "https:";
const ca = tls ? await readFile(caPath) : undefined;
const transport = tls ? https : http;
let cookies = new Map(),
  checks = [];
function request(method, path, body, extra = {}) {
  return new Promise((resolve, reject) => {
    const headers = { Accept: "application/json", ...extra };
    if (cookies.size)
      headers.Cookie = [...cookies].map(([k, v]) => `${k}=${v}`).join("; ");
    if (method === "POST" && !Object.hasOwn(headers, "Origin"))
      headers.Origin = origin;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const req = transport.request(
      new URL(path, origin),
      { method, ca, rejectUnauthorized: true, headers },
      (res) => {
        for (const raw of res.headers["set-cookie"] || []) {
          const [pair] = raw.split(";");
          const at = pair.indexOf("=");
          if (at > 0) {
            const name = pair.slice(0, at),
              value = pair.slice(at + 1);
            if (value) cookies.set(name, value);
            else cookies.delete(name);
          }
        }
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => {
          const text = Buffer.concat(chunks).toString("utf8");
          let value;
          try {
            value = JSON.parse(text);
          } catch {
            value = text;
          }
          resolve({
            status: res.statusCode,
            headers: res.headers,
            body: value,
          });
        });
      },
    );
    req.setTimeout(20000, () =>
      req.destroy(new Error("Preview request timed out")),
    );
    req.on("error", reject);
    if (body !== undefined) req.write(JSON.stringify(body));
    req.end();
  });
}
async function expect(method, path, status, body, headers) {
  const response = await request(method, path, body, headers);
  assert.equal(
    response.status,
    status,
    `${method} ${path}: ${response.status} ${typeof response.body === "object" ? response.body.code : ""}`,
  );
  return response;
}
async function csrf() {
  const r = await expect("GET", "/api/v2/auth/csrf", 200);
  assert.equal(r.body.headerName, "X-CSRF-TOKEN");
  assert.match(r.headers["cache-control"], /no-store/);
  return r.body.token;
}
function done(name) {
  checks.push(name);
  console.log(`PASS ${name}`);
}
const deltaDate = (s, days) => {
  const d = new Date(s + "T12:00:00Z");
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
};
const selection = {
  roomId: ready.roomId,
  checkInDate: deltaDate(ready.checkInDate, 3),
  checkOutDate: deltaDate(ready.checkOutDate, 3),
  guests: 2,
};
const city = await expect("GET", "/api/v2/cities", 200);
assert.ok(city.body.items.some((c) => c.code === "moscow"));
const page = await expect(
  "GET",
  `/demo/hotels?city=moscow&checkInDate=${selection.checkInDate}&checkOutDate=${selection.checkOutDate}&guests=2`,
  200,
);
assert.match(page.body, /Северный свет/);
assert.doesNotMatch(page.body, /Проверка интерфейса: тестовый API/);
done("real server-rendered catalogue over configured transport");
await expect("GET", "/api/v2/auth/me", 401);
let token = await csrf();
const cookie =
  (await request("GET", "/api/v2/auth/csrf")).headers["set-cookie"] || [];
// Spring can reuse the existing session without reissuing the cookie; inspect the original on a fresh jar.
const previous = cookies;
cookies = new Map();
const anonymous = await request("GET", "/api/v2/auth/csrf");
assert.ok(
  anonymous.headers["set-cookie"].some(
    (c) =>
      c.startsWith(tls ? "__Host-hotel-session=" : "hotel-local-session=") &&
      /;\s*Secure/i.test(c) === tls &&
      /;\s*HttpOnly/i.test(c) &&
      /;\s*Path=\//i.test(c),
  ),
);
token = anonymous.body.token;
await expect(
  "POST",
  "/api/v2/auth/login",
  403,
  { username: ready.username, password: ready.password },
  { Origin: "https://foreign.invalid", "X-CSRF-TOKEN": token },
);
await expect("POST", "/api/v2/auth/login", 403, {
  username: ready.username,
  password: ready.password,
});
await expect(
  "POST",
  "/api/v2/auth/login",
  200,
  { username: ready.username, password: ready.password },
  { "X-CSRF-TOKEN": token },
);
token = await csrf();
const session = await expect("GET", "/api/v2/auth/me", 200);
assert.equal(session.body.user.username, ready.username);
done("Spring JDBC session, transport-appropriate HttpOnly cookie, Origin and CSRF guards");
const quoteKey = randomUUID(),
  createKey = randomUUID();
const q = await expect("POST", "/api/v2/bookings/quote", 201, selection, {
  "X-CSRF-TOKEN": token,
  "Idempotency-Key": quoteKey,
});
assert.equal(q.body.pricing.totalPrice, "10000.00");
assert.equal(q.body.holdsRoom, false);
const qReplay = await expect("POST", "/api/v2/bookings/quote", 201, selection, {
  "X-CSRF-TOKEN": token,
  "Idempotency-Key": quoteKey,
});
assert.equal(qReplay.body.quoteToken, q.body.quoteToken);
done("server quote and replay use exact decimal price");
const created = await expect(
  "POST",
  "/api/v2/bookings",
  201,
  { ...selection, quoteToken: q.body.quoteToken },
  { "X-CSRF-TOKEN": token, "Idempotency-Key": createKey },
);
const id = created.body.bookingId;
assert.ok(id);
const result = await expect(
  "GET",
  "/api/v2/me/booking-operations/result",
  200,
  undefined,
  { "Idempotency-Key": createKey },
);
assert.equal(result.body.state, "SUCCEEDED");
assert.equal(result.body.receipt.bookingId, id);
const replay = await expect("POST", "/api/v2/bookings", 201, undefined, {
  "X-CSRF-TOKEN": token,
  "Idempotency-Key": createKey,
});
assert.deepEqual(replay.body, created.body);
done("confirmed booking, durable result lookup and bodyless replay");
const selectedQuery = new URLSearchParams({
  checkInDate: selection.checkInDate,
  checkOutDate: selection.checkOutDate,
  guests: "2",
});
const occupied = await expect(
  "GET",
  `/api/v2/rooms/${ready.roomId}?${selectedQuery}`,
  200,
);
assert.equal(occupied.body.availability, "UNAVAILABLE");
const booking = await expect("GET", `/api/v2/me/bookings/${id}`, 200);
assert.equal(booking.body.status, "CONFIRMED");
assert.equal(booking.body.pricing.totalPrice, "10000.00");
assert.equal(booking.body.cancellation.canCancel, true);
const list = await expect("GET", "/api/v2/me/bookings?status=CONFIRMED", 200);
assert.equal(list.body.items.filter((b) => b.id === id).length, 1);
done("owner list, saved snapshot and occupied dates");
const unknownKey = randomUUID();
await expect("GET", "/api/v2/me/booking-operations/result", 404, undefined, {
  "Idempotency-Key": unknownKey,
});
const fenced = await expect(
  "POST",
  "/api/v2/me/booking-operations/fence",
  200,
  undefined,
  { "X-CSRF-TOKEN": token, "Idempotency-Key": unknownKey },
);
assert.equal(fenced.body.state, "FAILED");
assert.equal(fenced.body.errorCode, "REQUEST_ABORTED");
await expect(
  "POST",
  "/api/v2/bookings",
  503,
  { ...selection, quoteToken: q.body.quoteToken },
  { "X-CSRF-TOKEN": token, "Idempotency-Key": unknownKey },
);
done("404 is resolved by fence; delayed request cannot create a booking");
await expect("POST", `/api/v2/me/bookings/${id}/cancel`, 200, undefined, {
  "X-CSRF-TOKEN": token,
});
const cancelled = await expect(
  "POST",
  `/api/v2/me/bookings/${id}/cancel`,
  200,
  undefined,
  { "X-CSRF-TOKEN": token },
);
assert.equal(cancelled.body.status, "CANCELLED");
assert.equal(cancelled.body.pricing.totalPrice, "10000.00");
const free = await expect(
  "GET",
  `/api/v2/rooms/${ready.roomId}?${selectedQuery}`,
  200,
);
assert.equal(free.body.availability, "AVAILABLE");
const history = await expect(
  "GET",
  "/api/v2/me/bookings?status=CANCELLED",
  200,
);
assert.ok(history.body.items.some((b) => b.id === id));
const afterCancelReplay = await expect(
  "POST",
  "/api/v2/bookings",
  201,
  undefined,
  { "X-CSRF-TOKEN": token, "Idempotency-Key": createKey },
);
assert.deepEqual(afterCancelReplay.body, created.body);
done(
  "idempotent cancellation releases dates and preserves original receipt/history",
);
await expect("POST", "/api/v2/auth/logout", 204, undefined, {
  "X-CSRF-TOKEN": token,
});
await expect("GET", "/api/v2/auth/me", 401);
assert.equal(cookies.has(tls ? "__Host-hotel-session" : "hotel-local-session"), false);
done("logout invalidates the real session and clears the browser cookie");
await writeFile(
  reportPath,
  JSON.stringify(
    {
      completedAt: new Date().toISOString(),
      origin,
      backend: "real Spring + isolated Testcontainers",
      tlsVerification: tls ? "explicit local certificate, rejectUnauthorized=true" : "local HTTP (no TLS)",
      bookingId: id,
      selection,
      checks,
    },
    null,
    2,
  ) + "\n",
);
