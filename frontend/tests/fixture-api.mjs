// Explicit loopback-only frontend QA double; never a normal runtime fallback.
import http from "node:http";
import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
const examples = JSON.parse(
  await readFile(
    new URL("../../docs/api/examples.json", import.meta.url),
    "utf8",
  ),
);
const fixtures = Object.fromEntries(
  examples.examples.map((e) => [e.name, e.value]),
);
const sessions = new Set();
const bookingRows = new Map(),
  operations = new Map(),
  quoteRows = new Map();
let nextId = 1000;
const csrf = "frontend-qa-token-".repeat(3);
const clone = (name) => structuredClone(fixtures[name]);
const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, "http://127.0.0.1:18081"),
    path = u.pathname;
  res.setHeader("Content-Type", "application/json");
  res.setHeader("Cache-Control", "private, no-store");
  res.setHeader("X-Trace-Id", randomUUID().replaceAll("-", ""));
  const send = (status, value) => {
    res.statusCode = status;
    res.end(status === 204 ? undefined : JSON.stringify(value));
  };
  const error = (status, code, message) =>
    send(status, {
      status,
      code,
      message,
      traceId: randomUUID().replaceAll("-", ""),
      details: {},
      fieldErrors: [],
    });
  const sid = /qa-fixture-session=([a-z0-9-]+)/.exec(
    req.headers.cookie || "",
  )?.[1];
  if (path === "/api/v2/auth/csrf")
    return send(200, {
      headerName: "X-CSRF-TOKEN",
      token: csrf,
      expiresAt: new Date(Date.now() + 1800000).toISOString(),
      registrationMode: "CLOSED",
    });
  if (path === "/api/v2/auth/me") {
    if (!sessions.has(sid))
      return error(
        401,
        "AUTHENTICATION_REQUIRED",
        "Войдите, чтобы продолжить.",
      );
    return send(200, {
      ...clone("session"),
      idleExpiresAt: new Date(Date.now() + 1800000).toISOString(),
      absoluteExpiresAt: new Date(Date.now() + 43200000).toISOString(),
    });
  }
  if (path === "/api/v2/auth/login" && req.method === "POST") {
    if (req.headers["x-csrf-token"] !== csrf)
      return error(403, "CSRF_INVALID", "Обновите защиту формы.");
    let text = "";
    for await (const chunk of req) {
      text += chunk;
      if (text.length > 16384)
        return error(413, "PAYLOAD_TOO_LARGE", "Слишком большой запрос.");
    }
    let data;
    try {
      data = JSON.parse(text);
    } catch {
      return error(400, "MALFORMED_JSON", "Некорректный запрос.");
    }
    if (data.username !== "qa-user" || data.password !== "qa-only-password")
      return error(
        401,
        "INVALID_CREDENTIALS",
        "Неверное имя пользователя или пароль.",
      );
    const id = randomUUID();
    sessions.add(id);
    res.setHeader(
      "Set-Cookie",
      `qa-fixture-session=${id}; HttpOnly; SameSite=Lax; Path=/`,
    );
    return send(200, clone("session"));
  }
  if (path === "/api/v2/auth/logout" && req.method === "POST") {
    if (req.headers["x-csrf-token"] !== csrf)
      return error(403, "CSRF_INVALID", "Обновите защиту формы.");
    sessions.delete(sid);
    res.setHeader(
      "Set-Cookie",
      "qa-fixture-session=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0",
    );
    return send(204);
  }
  if (
    path === "/api/v2/bookings/quote" ||
    path === "/api/v2/bookings" ||
    path.startsWith("/api/v2/me/")
  ) {
    if (!sessions.has(sid))
      return error(
        401,
        "AUTHENTICATION_REQUIRED",
        "Войдите, чтобы продолжить.",
      );
    const key = req.headers["idempotency-key"];
    if (req.method === "POST" && req.headers["x-csrf-token"] !== csrf)
      return error(403, "CSRF_INVALID", "Обновите защиту формы.");
    if (path === "/api/v2/bookings/quote" && req.method === "POST") {
      let text = "";
      for await (const c of req) text += c;
      const stay = JSON.parse(text);
      if (quoteRows.has(key)) return send(201, quoteRows.get(key));
      const nights = Math.round(
        (Date.parse(stay.checkOutDate) - Date.parse(stay.checkInDate)) /
          86400000,
      );
      const q = {
        ...clone("quote"),
        stay,
        quoteToken: randomUUID() + randomUUID(),
        createdAt: new Date().toISOString(),
        expiresAt: new Date(Date.now() + 600000).toISOString(),
        pricing: {
          ...clone("quote").pricing,
          nights,
          totalPrice: (5000 * nights).toFixed(2),
        },
        cancellationDeadlineAt: stay.checkInDate + "T00:00:00Z",
      };
      quoteRows.set(key, q);
      return send(201, q);
    }
    if (path === "/api/v2/bookings" && req.method === "POST") {
      if (operations.has(key)) {
        const op = operations.get(key);
        if (op.state === "SUCCEEDED") return send(201, op.receipt);
        return error(
          503,
          "REQUEST_ABORTED",
          "Операция закрыта без создания брони.",
        );
      }
      let text = "";
      for await (const c of req) text += c;
      if (!text) return error(400, "BODY_REQUIRED", "Нужны данные брони.");
      const body = JSON.parse(text),
        q = [...quoteRows.values()].find(
          (q) => q.quoteToken === body.quoteToken,
        );
      if (!q) return error(409, "QUOTE_INVALID", "Получите новый расчёт.");
      const id = String(++nextId),
        createdAt = new Date().toISOString();
      const receipt = {
        ...clone("receipt"),
        bookingId: id,
        createdAt,
        stay: q.stay,
        pricing: q.pricing,
        snapshot: q.snapshot,
        cancellationDeadlineAt: q.cancellationDeadlineAt,
        bookingUrl: "/api/v2/me/bookings/" + id,
      };
      bookingRows.set(id, {
        ...clone("booking"),
        id,
        roomId: body.roomId,
        checkInDate: body.checkInDate,
        checkOutDate: body.checkOutDate,
        guests: body.guests,
        pricing: q.pricing,
        snapshot: q.snapshot,
        createdAt,
        cancellation: {
          canCancel: true,
          deadlineAt: q.cancellationDeadlineAt,
          timeZone: q.snapshot.timeZone,
          reason: null,
        },
      });
      operations.set(key, {
        key,
        operation: "CREATE_BOOKING",
        state: "SUCCEEDED",
        createdAt,
        completedAt: createdAt,
        receipt,
      });
      return send(201, receipt);
    }
    if (path === "/api/v2/me/booking-operations/result") {
      if (!operations.has(key))
        return error(404, "NOT_FOUND", "Операция не найдена.");
      return send(200, operations.get(key));
    }
    if (
      path === "/api/v2/me/booking-operations/fence" &&
      req.method === "POST"
    ) {
      if (!operations.has(key))
        operations.set(key, {
          ...clone("fencedOperation"),
          key,
          createdAt: new Date().toISOString(),
          completedAt: new Date().toISOString(),
        });
      return send(200, operations.get(key));
    }
    if (path === "/api/v2/me/bookings") {
      let items = [...bookingRows.values()].reverse();
      if (u.searchParams.has("status"))
        items = items.filter((b) => b.status === u.searchParams.get("status"));
      const totalElements = items.length,
        page = Number(u.searchParams.get("page") || 0);
      return send(200, {
        items: items.slice(page * 20, page * 20 + 20),
        page,
        size: 20,
        totalElements,
        totalPages: Math.ceil(totalElements / 20),
        sort: "createdDesc",
        evaluatedAt: new Date().toISOString(),
      });
    }
    const match = path.match(
      /^\/api\/v2\/me\/bookings\/([1-9][0-9]*)(\/cancel)?$/,
    );
    if (match) {
      const b = bookingRows.get(match[1]);
      if (!b) return error(404, "NOT_FOUND", "Бронь не найдена.");
      if (match[2] && req.method === "POST") {
        b.status = "CANCELLED";
        b.cancelledAt = new Date().toISOString();
        b.cancellation = {
          ...b.cancellation,
          canCancel: false,
          reason: "ALREADY_CANCELLED",
        };
      }
      return send(200, b);
    }
  }
  if (path === "/api/v2/cities") return send(200, clone("cityList"));
  const dates = u.searchParams.has("checkInDate");
  if (path === "/api/v2/hotels") {
    const page = clone("hotelPage");
    page.page = Number(u.searchParams.get("page") || 0);
    if (
      (u.searchParams.get("city") && u.searchParams.get("city") !== "moscow") ||
      Number(u.searchParams.get("guests") || 1) > 2 ||
      Number(u.searchParams.get("priceMax") || 999999) < 5000 ||
      page.page > 0
    ) {
      page.items = [];
      page.totalElements = 0;
      page.totalPages = 0;
    }
    for (const h of page.items) {
      h.availabilityEvaluated = dates;
      if (!dates)
        h.fromPrice = {
          ...h.fromPrice,
          nights: 1,
          totalPrice: h.fromPrice.nightlyPrice,
        };
    }
    return send(200, page);
  }
  if (path === "/api/v2/hotels/demo-severny-svet")
    return send(200, clone("hotel"));
  if (path === "/api/v2/hotels/demo-severny-svet/rooms") {
    const page = clone("roomPage");
    for (const r of page.items)
      if (!dates) {
        r.availability = "NOT_EVALUATED";
        r.pricing = {
          ...r.pricing,
          nights: 1,
          totalPrice: r.pricing.nightlyPrice,
        };
      }
    return send(200, page);
  }
  return error(404, "NOT_FOUND", "Объект не найден.");
});
server.listen(18081, "127.0.0.1", () =>
  console.log("Frontend QA API: http://127.0.0.1:18081 (test fixtures only)"),
);
for (const signal of ["SIGINT", "SIGTERM"])
  process.on(signal, () => server.close(() => process.exit(0)));
