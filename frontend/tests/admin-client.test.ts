import assert from "node:assert/strict";
import test from "node:test";
import { createAdminClient } from "../src/lib/admin-client";
import { ApiClientError } from "../src/lib/api-client";
import type { AdminHotelDraft } from "../src/lib/admin-types";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
const csrf = {
  headerName: "X-CSRF-TOKEN",
  token: "a".repeat(32),
  expiresAt: new Date(Date.now() + 60000).toISOString(),
};
const draft: AdminHotelDraft = {
  version: null,
  slug: "test-hotel",
  name: "Test",
  city: "Москва",
  cityCode: "moscow",
  timeZone: "Europe/Moscow",
  address: "Улица, 1",
  description: "Описание",
  distanceFromCityCenter: 1,
  photos: [],
  published: false,
};

test("admin mutations bootstrap CSRF, send exact draft and do not retry conflicts", async () => {
  const calls: { path: string; init: RequestInit }[] = [];
  const api = createAdminClient(async (input, init = {}) => {
    calls.push({ path: String(input), init });
    return String(input).endsWith("/auth/csrf")
      ? json(csrf)
      : json({ code: "RESOURCE_CONFLICT" }, 409);
  });
  await assert.rejects(
    api.saveHotel("42", { ...draft, version: 7 }),
    (error: unknown) =>
      error instanceof ApiClientError &&
      error.status === 409 &&
      error.message.includes("Перезагрузите"),
  );
  assert.equal(calls.length, 2);
  assert.equal(calls[1].path, "/api/v2/admin/hotels/42");
  assert.equal(calls[1].init.method, "POST");
  assert.equal(calls[1].init.credentials, "same-origin");
  assert.equal(calls[1].init.cache, "no-store");
  assert.equal(
    new Headers(calls[1].init.headers).get("X-CSRF-TOKEN"),
    csrf.token,
  );
  assert.equal(
    new Headers(calls[1].init.headers).get("Content-Type"),
    "application/json",
  );
  assert.deepEqual(JSON.parse(String(calls[1].init.body)), {
    ...draft,
    version: 7,
  });
});

test("unknown save outcome is reported without automatically retrying creation", async () => {
  let mutations = 0;
  const api = createAdminClient(async (input) => {
    if (String(input).endsWith("/auth/csrf")) return json(csrf);
    mutations++;
    throw new TypeError("offline");
  });
  await assert.rejects(
    api.saveHotel(null, draft),
    (error: unknown) =>
      error instanceof ApiClientError &&
      error.status === 0 &&
      error.message.includes("дубль"),
  );
  assert.equal(mutations, 1);
});

test("multipart upload sends CSRF without overriding browser boundary", async () => {
  let upload: RequestInit | undefined;
  const api = createAdminClient(async (input, init) => {
    if (String(input).endsWith("/auth/csrf")) return json(csrf);
    assert.equal(String(input), "/api/v2/admin/media");
    upload = init;
    return json({ url: "/media/test.png" }, 201);
  });
  assert.deepEqual(
    await api.upload(
      new File([new Uint8Array([1, 2])], "room.png", { type: "image/png" }),
    ),
    { url: "/media/test.png" },
  );
  assert.equal(new Headers(upload?.headers).get("Content-Type"), null);
  assert.equal(new Headers(upload?.headers).get("X-CSRF-TOKEN"), csrf.token);
  assert.ok(upload?.body instanceof FormData);
  assert.ok(upload.body.get("file") instanceof File);
});

test("admin paths validate IDs and pagination before network; rooms remain pageable", async () => {
  const calls: string[] = [];
  const api = createAdminClient(async (input) => {
    calls.push(String(input));
    return json({ items: [] });
  });
  for (const bad of ["../auth", "1?x=2", "0", "1/rooms", "1#x", "01"]) {
    assert.throws(() => api.hotel(bad), TypeError);
    assert.throws(() => api.rooms(bad), TypeError);
  }
  assert.throws(() => api.hotels(-1), TypeError);
  assert.throws(() => api.rooms("2", 0, 101), TypeError);
  assert.throws(() => api.hotels(0.1), TypeError);
  assert.equal(calls.length, 0);
  await api.rooms("2", 6, 20);
  assert.equal(calls[0], "/api/v2/admin/hotels/2/rooms?page=6&size=20");
});

test("uploads reject unsupported, empty and oversized files before CSRF or network", () => {
  const api = createAdminClient(async () => {
    assert.fail("No network expected");
  });
  assert.throws(() =>
    api.upload(new File(["x"], "bad.svg", { type: "image/svg+xml" })),
  );
  assert.throws(() =>
    api.upload(new File([], "empty.png", { type: "image/png" })),
  );
  assert.throws(() =>
    api.upload(
      new File([new Uint8Array(5 * 1024 * 1024 + 1)], "big.jpg", {
        type: "image/jpeg",
      }),
    ),
  );
});

test("HTML server errors and unsafe messages never appear in admin errors", async () => {
  const api = createAdminClient(
    async () =>
      new Response("<html>database password internal</html>", { status: 503 }),
  );
  await assert.rejects(
    api.hotels(),
    (error: unknown) =>
      error instanceof ApiClientError &&
      !error.message.includes("password") &&
      error.message.includes("Сервис"),
  );
});

test("invalid success after a save is uncertain and must not permit an automatic retry", async () => {
  let writes = 0;
  const api = createAdminClient(async (url) => {
    if (String(url).endsWith("/auth/csrf"))
      return Response.json({
        token: "a".repeat(32),
        headerName: "X-CSRF-TOKEN",
        expiresAt: new Date(Date.now() + 600000).toISOString(),
        registrationMode: "CLOSED",
      });
    writes++;
    return Response.json({});
  });
  await assert.rejects(
    api.saveHotel(null, {} as never),
    (error: unknown) => error instanceof ApiClientError && error.status === 502,
  );
  assert.equal(writes, 1);
});
