import assert from "node:assert/strict";
import test from "node:test";
import { ApiClientError, createApiClient } from "../src/lib/api-client";

const token = (value: string) => ({
  headerName: "X-CSRF-TOKEN",
  token: value.repeat(32),
  expiresAt: new Date(Date.now() + 60_000).toISOString(),
});
const json = (
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
const errorBody = (code: string, status: number) => ({
  status,
  code,
  message: "Request rejected",
  traceId: "trace-1",
  fieldErrors: [],
  details: {},
});
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
};

test("deduplicates CSRF bootstrap and sends same-origin, uncached requests", async () => {
  const pending = deferred<Response>();
  const calls: Array<{ url: string; init: RequestInit }> = [];
  const client = createApiClient(async (input, init = {}) => {
    calls.push({ url: String(input), init });
    return pending.promise;
  });
  const first = client.csrf();
  const second = client.csrf();
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(calls.length, 1);
  pending.resolve(json(token("a")));
  assert.deepEqual(await first, await second);
  assert.equal(calls[0].url, "/api/v2/auth/csrf");
  assert.equal(calls[0].init.credentials, "same-origin");
  assert.equal(calls[0].init.cache, "no-store");
  assert.equal(
    new Headers(calls[0].init.headers).get("Accept"),
    "application/json",
  );
  assert.deepEqual(await client.csrf(), await first);
  assert.equal(calls.length, 1);
});

test("login rotates CSRF state and fetches the authoritative session", async () => {
  const calls: Array<{ url: string; init: RequestInit }> = [];
  let csrfCount = 0;
  const session = { authenticated: true, username: "guest" };
  const client = createApiClient(async (input, init = {}) => {
    const url = String(input);
    calls.push({ url, init });
    if (url.endsWith("/auth/csrf"))
      return json(token(++csrfCount === 1 ? "a" : "b"));
    if (url.endsWith("/auth/login")) return json(session);
    if (url.endsWith("/auth/me")) return json(session);
    throw new Error(`Unexpected request ${url}`);
  });
  assert.deepEqual(
    await client.login({ username: "guest", password: "secret" }),
    session,
  );
  assert.deepEqual(
    calls.map(({ url, init }) => [url, init.method]),
    [
      ["/api/v2/auth/csrf", "GET"],
      ["/api/v2/auth/login", "POST"],
      ["/api/v2/auth/csrf", "GET"],
      ["/api/v2/auth/me", "GET"],
    ],
  );
  assert.equal(calls[1].url.includes("secret"), false);
  assert.deepEqual(JSON.parse(String(calls[1].init.body)), {
    username: "guest",
    password: "secret",
  });
  assert.equal(
    new Headers(calls[1].init.headers).get("X-CSRF-TOKEN"),
    "a".repeat(32),
  );
});

test("logout uses CSRF and clears the cached token after 204", async () => {
  let csrfCount = 0;
  const calls: string[] = [];
  const client = createApiClient(async (input) => {
    const url = String(input);
    calls.push(url);
    if (url.endsWith("/auth/csrf"))
      return json(token(String.fromCharCode(97 + csrfCount++)));
    if (url.endsWith("/auth/logout"))
      return new Response(null, { status: 204 });
    throw new Error(`Unexpected request ${url}`);
  });
  await client.csrf();
  await client.logout();
  await client.csrf();
  assert.deepEqual(calls, [
    "/api/v2/auth/csrf",
    "/api/v2/auth/logout",
    "/api/v2/auth/csrf",
  ]);
});

test("already expired server CSRF fails once instead of polling indefinitely", async () => {
  let calls = 0;
  const client = createApiClient(async () => {
    calls++;
    return json({ ...token("a"), expiresAt: "2000-01-01T00:00:00Z" });
  });
  await assert.rejects(
    client.csrf(),
    (e: unknown) =>
      e instanceof ApiClientError && e.code === "INVALID_RESPONSE",
  );
  assert.equal(calls, 1);
});

test("GET rejects mutation and unsafe paths before making a request", async () => {
  let count = 0;
  const client = createApiClient(async () => {
    count += 1;
    return json({});
  });
  for (const path of [
    "//evil",
    "https://evil",
    "/auth/login",
    "/auth/logout",
    "/hotels/../auth/me",
    "/hotels/a#x",
    "/unknown",
  ]) {
    await assert.rejects(client.get(path), TypeError);
  }
  assert.equal(count, 0);
  await client.get("/hotels?city=sochi&page=1");
  assert.equal(count, 1);
});

test("hides HTML gateway bodies and parses only strict numeric Retry-After", async () => {
  const client = createApiClient(
    async () =>
      new Response("<html>private proxy detail</html>", {
        status: 502,
        headers: { "Content-Type": "text/html", "Retry-After": "12x" },
      }),
  );
  await assert.rejects(client.get("/cities"), (error: unknown) => {
    assert.ok(error instanceof ApiClientError);
    assert.equal(error.message.includes("private proxy detail"), false);
    assert.equal(error.status, 502);
    assert.equal(error.retryAfterSeconds, null);
    return true;
  });
  const retryClient = createApiClient(async () =>
    json(errorBody("RATE_LIMITED", 429), 429, { "Retry-After": "12" }),
  );
  await assert.rejects(
    retryClient.get("/cities"),
    (error: unknown) =>
      error instanceof ApiClientError && error.retryAfterSeconds === 12,
  );
});

test("does not retry CSRF failures and refreshes the token for the next explicit action", async () => {
  let csrfCount = 0;
  const calls: string[] = [];
  const client = createApiClient(async (input) => {
    const url = String(input);
    calls.push(url);
    if (url.endsWith("/auth/csrf"))
      return json(token(String.fromCharCode(97 + csrfCount++)));
    return json(errorBody("CSRF_INVALID", 403), 403);
  });
  await assert.rejects(
    client.login({ username: "guest", password: "secret" }),
    ApiClientError,
  );
  assert.equal(calls.filter((url) => url.endsWith("/auth/login")).length, 1);
  await client.csrf();
  assert.equal(calls.filter((url) => url.endsWith("/auth/csrf")).length, 2);
});

test("converts network failures to a safe localized error", async () => {
  const client = createApiClient(async () => {
    throw new Error("secret host and stack details");
  });
  await assert.rejects(client.get("/cities"), (error: unknown) => {
    assert.ok(error instanceof ApiClientError);
    assert.equal(error.status, 0);
    assert.match(error.message, /подключение/);
    assert.equal(error.message.includes("secret host"), false);
    return true;
  });
});

const KEY = "123e4567-e89b-42d3-a456-426614174000";
const csrfReply = () => json(token("c"));

test("quote and create send UUID keys only in headers with current CSRF", async () => {
  const calls: Array<{ url: string; init: RequestInit }> = [];
  const client = createApiClient(async (input, init = {}) => {
    const url = String(input);
    calls.push({ url, init });
    if (url.endsWith("/auth/csrf")) return csrfReply();
    return json({ accepted: true });
  });
  const selection = {
    roomId: "7",
    checkInDate: "2026-11-01",
    checkOutDate: "2026-11-03",
    guests: 2,
  };
  await client.quote(selection, KEY);
  await client.createBooking({ ...selection, quoteToken: "opaque-quote" }, KEY);
  for (const call of calls.filter(({ url }) => !url.endsWith("/auth/csrf"))) {
    assert.equal(call.url.includes(KEY), false);
    assert.equal(new Headers(call.init.headers).get("Idempotency-Key"), KEY);
    assert.equal(
      new Headers(call.init.headers).get("X-CSRF-TOKEN"),
      "c".repeat(32),
    );
  }
  assert.deepEqual(JSON.parse(String(calls[1].init.body)), selection);
});

test("bodyless create replay keeps its original key and sends no body", async () => {
  let captured: { url: string; init: RequestInit } | undefined;
  const client = createApiClient(async (input, init = {}) => {
    if (String(input).endsWith("/auth/csrf")) return csrfReply();
    captured = { url: String(input), init };
    return json({ replay: true });
  });
  await client.createBooking(null, KEY);
  assert.equal(captured?.url, "/api/v2/bookings");
  assert.equal(captured?.init.body, undefined);
  assert.equal(new Headers(captured?.init.headers).get("Idempotency-Key"), KEY);
  assert.equal(new Headers(captured?.init.headers).has("Content-Type"), false);
});

test("operation recovery uses key headers, fences with CSRF, and never retries mutations", async () => {
  const calls: Array<{ url: string; init: RequestInit }> = [];
  const client = createApiClient(async (input, init = {}) => {
    const url = String(input);
    calls.push({ url, init });
    if (url.endsWith("/auth/csrf")) return csrfReply();
    if (url.endsWith("/result")) return json({ state: "PROCESSING" });
    return json(errorBody("RATE_LIMITED", 429), 429, { "Retry-After": "5" });
  });
  await client.bookingResult(KEY);
  await assert.rejects(client.fenceBooking(KEY), ApiClientError);
  assert.equal(calls.filter(({ url }) => url.endsWith("/fence")).length, 1);
  const result = calls.find(({ url }) => url.endsWith("/result"))!;
  assert.equal(result.url.includes(KEY), false);
  assert.equal(new Headers(result.init.headers).get("Idempotency-Key"), KEY);
  const fence = calls.find(({ url }) => url.endsWith("/fence"))!;
  assert.equal(new Headers(fence.init.headers).get("Idempotency-Key"), KEY);
  assert.equal(new Headers(fence.init.headers).has("X-CSRF-TOKEN"), true);
});

test("own booking methods validate IDs and cancel once with CSRF", async () => {
  const calls: Array<{ url: string; init: RequestInit }> = [];
  const client = createApiClient(async (input, init = {}) => {
    const url = String(input);
    calls.push({ url, init });
    if (url.endsWith("/auth/csrf")) return csrfReply();
    return json({});
  });
  await assert.rejects(client.ownBooking("0"), TypeError);
  await assert.rejects(client.cancelBooking("2/3"), TypeError);
  assert.equal(calls.length, 0);
  await client.ownBookings({ page: 2, status: "CANCELLED" });
  await client.ownBooking("17");
  await client.cancelBooking("17");
  assert.equal(calls[0].url, "/api/v2/me/bookings?page=2&status=CANCELLED");
  assert.equal(calls[1].url, "/api/v2/me/bookings/17");
  assert.equal(calls.filter(({ url }) => url.endsWith("/17/cancel")).length, 1);
  assert.equal(
    new Headers(calls.at(-1)?.init.headers).has("X-CSRF-TOKEN"),
    true,
  );
});

test("rejects non-v4 keys before network access", async () => {
  let calls = 0;
  const client = createApiClient(async () => {
    calls++;
    return json({});
  });
  await assert.rejects(
    client.quote(
      {
        roomId: "1",
        checkInDate: "2026-11-01",
        checkOutDate: "2026-11-02",
        guests: 1,
      },
      "not-a-uuid",
    ),
    TypeError,
  );
  await assert.rejects(
    client.bookingResult("123e4567-e89b-12d3-a456-426614174000"),
    TypeError,
  );
  assert.equal(calls, 0);
});

test("preserves only documented safe replacement quote details", async () => {
  const quote = {
    quoteToken: "replacement",
    expiresAt: "2026-10-22T12:00:00Z",
  };
  const client = createApiClient(async () =>
    json(
      {
        ...errorBody("PRICE_CHANGED", 409),
        details: { replacementQuote: quote },
      },
      409,
    ),
  );
  await assert.rejects(client.get("/cities"), (error: unknown) => {
    assert.ok(error instanceof ApiClientError);
    assert.deepEqual(error.details?.replacementQuote, quote);
    return true;
  });
});
