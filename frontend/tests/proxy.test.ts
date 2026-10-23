import { test } from "node:test";
import assert from "node:assert/strict";
import { proxyApi } from "../src/lib/proxy";
const env: NodeJS.ProcessEnv = {
  PUBLIC_ORIGIN: "https://hotel.example",
  BROWSER_BACKEND_ORIGIN: "http://127.0.0.1:8080",
  NODE_ENV: "production",
};
const post = (origin: string | null = "https://hotel.example") =>
  new Request("https://hotel.example/api/v2/auth/login", {
    method: "POST",
    headers: {
      ...(origin ? { Origin: origin } : {}),
      "Content-Type": "application/json",
      "X-CSRF-TOKEN": "test-csrf",
      Cookie: "__Host-hotel-session=opaque",
      Authorization: "Basic never-forward",
      "X-Forwarded-Host": "attacker.test",
    },
    body: JSON.stringify({ username: "user", password: "password" }),
  });
test("foreign and missing browser Origins cannot be translated into trusted upstream requests", async () => {
  for (const origin of [
    null,
    "https://evil.test",
    "null",
    "http://hotel.example",
  ]) {
    let calls = 0;
    const r = await proxyApi(
      post(origin),
      ["auth", "login"],
      async () => {
        calls++;
        return new Response();
      },
      env,
    );
    assert.equal(r.status, 403);
    assert.equal(calls, 0);
  }
});
test("validated origin, CSRF and cookies are forwarded only to configured backend; multiple Set-Cookie survive", async () => {
  const r = await proxyApi(
    post(),
    ["auth", "login"],
    async (input, init) => {
      assert.equal(String(input), "http://127.0.0.1:8080/api/v2/auth/login");
      const h = new Headers(init?.headers);
      assert.equal(h.get("Origin"), "http://127.0.0.1:8080");
      assert.equal(h.get("X-CSRF-TOKEN"), "test-csrf");
      assert.equal(h.get("Authorization"), null);
      assert.equal(h.get("X-Forwarded-Host"), null);
      assert.match(h.get("Cookie") || "", /opaque/);
      const headers = new Headers({ "Content-Type": "application/json" });
      headers.append("Set-Cookie", "one=a; Secure; HttpOnly; Path=/");
      headers.append("Set-Cookie", "two=b; Secure; Path=/");
      return new Response("{}", { headers });
    },
    env,
  );
  assert.equal(r.headers.getSetCookie().length, 2);
  assert.equal(r.headers.get("cache-control"), "private, no-store");
});
test("proxy rejects unsupported mutations and arbitrary upstream paths", async () => {
  for (const segments of [
    ["admin", "bookings"],
    ["auth", "register"],
    ["..", "admin"],
    ["https:", "evil.test"],
  ]) {
    let calls = 0;
    assert.equal(
      (
        await proxyApi(
          post(),
          segments,
          async () => {
            calls++;
            return new Response();
          },
          env,
        )
      ).status,
      404,
    );
    assert.equal(calls, 0);
  }
});
test("HTTP public origin is rejected outside explicit loopback development QA", async () => {
  assert.equal(
    (
      await proxyApi(post("http://hotel.example"), ["auth", "login"], fetch, {
        ...env,
        PUBLIC_ORIGIN: "http://hotel.example",
      })
    ).status,
    503,
  );
});
test("gateway HTML and redirects never expose upstream content or locations", async () => {
  for (const response of [
    new Response("<h1>internal secret</h1>", { status: 502 }),
    new Response(null, {
      status: 302,
      headers: { Location: "https://evil.test" },
    }),
  ]) {
    const r = await proxyApi(
      new Request("https://hotel.example/api/v2/auth/me"),
      ["auth", "me"],
      async () => response,
      env,
    );
    assert.equal(r.status, 502);
    assert.equal(r.headers.get("Location"), null);
    assert.doesNotMatch(await r.text(), /internal secret|evil.test/);
  }
});
test("oversized login body is rejected before contacting upstream", async () => {
  let called = false;
  const r = await proxyApi(
    new Request("https://hotel.example/api/v2/auth/login", {
      method: "POST",
      headers: { Origin: "https://hotel.example" },
      body: "x".repeat(16385),
    }),
    ["auth", "login"],
    async () => {
      called = true;
      return new Response();
    },
    env,
  );
  assert.equal(r.status, 413);
  assert.equal(called, false);
});

test("booking keys are forwarded on bodyless replay and result lookup without entering URLs", async () => {
  const key = "a935143b-b692-400c-8eb3-9c50ce180331";
  for (const [method, segments] of [
    ["POST", ["bookings"]],
    ["POST", ["me", "booking-operations", "fence"]],
    ["GET", ["me", "booking-operations", "result"]],
  ] as const) {
    const req = new Request(
      `https://hotel.example/api/v2/${segments.join("/")}`,
      {
        method,
        headers: {
          Origin: "https://hotel.example",
          "X-CSRF-TOKEN": "csrf",
          "Idempotency-Key": key,
        },
      },
    );
    const r = await proxyApi(
      req,
      [...segments],
      async (input, init) => {
        assert.equal(new Headers(init?.headers).get("Idempotency-Key"), key);
        assert.doesNotMatch(String(input), new RegExp(key));
        assert.equal(init?.body, undefined);
        return Response.json({ state: "SUCCEEDED" });
      },
      env,
    );
    assert.equal(r.status, 200);
  }
});

const localEnv: NodeJS.ProcessEnv = {
  NODE_ENV: "development",
  LOCAL_HTTP_MODE: "1",
  PUBLIC_ORIGIN: "http://127.0.0.1:3000",
  BROWSER_BACKEND_ORIGIN: "http://127.0.0.1:8080",
};
test("local HTTP translates the session cookie in both directions, including logout", async () => {
  for (const value of ["opaque", ""]) {
    const r = await proxyApi(
      new Request("http://127.0.0.1:3000/api/v2/auth/login", {
        method: "POST",
        headers: {
          Origin: localEnv.PUBLIC_ORIGIN!,
          Cookie:
            "hotel-local-session=local; __Host-hotel-session=stale; other=keep",
          "X-CSRF-TOKEN": "csrf",
        },
      }),
      ["auth", "login"],
      async (_, init) => {
        const headers = new Headers(init?.headers);
        assert.equal(
          headers.get("Cookie"),
          "__Host-hotel-session=local; other=keep",
        );
        assert.equal(headers.get("X-CSRF-TOKEN"), "csrf");
        const out = new Headers({ "Content-Type": "application/json" });
        out.append(
          "Set-Cookie",
          `__Host-hotel-session=${value}; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0`,
        );
        out.append("Set-Cookie", "other=unchanged; Secure");
        return new Response("{}", { headers: out });
      },
      localEnv,
    );
    assert.equal(r.status, 200);
    assert.deepEqual(r.headers.getSetCookie(), [
      `hotel-local-session=${value}; Path=/; HttpOnly; SameSite=Lax; Max-Age=0`,
      "other=unchanged; Secure",
    ]);
  }
});
test("local HTTP remains opt-in, development-only, loopback-only and checks Origin", async () => {
  for (const overrides of [
    { NODE_ENV: "production" as const },
    { LOCAL_HTTP_MODE: "0" },
    { PUBLIC_ORIGIN: "http://hotel.example" },
    { BROWSER_BACKEND_ORIGIN: "http://remote.example" },
  ]) {
    let calls = 0;
    const response = await proxyApi(
      new Request("http://127.0.0.1:3000/api/v2/auth/csrf"),
      ["auth", "csrf"],
      async () => {
        calls++;
        return Response.json({});
      },
      { ...localEnv, ...overrides },
    );
    assert.equal(response.status, 503);
    assert.equal(calls, 0);
  }
  for (const origin of [null, "http://localhost:3000", "http://evil.test"]) {
    let calls = 0;
    const response = await proxyApi(
      new Request("http://127.0.0.1:3000/api/v2/auth/login", {
        method: "POST",
        headers: origin ? { Origin: origin } : {},
      }),
      ["auth", "login"],
      async () => {
        calls++;
        return Response.json({});
      },
      localEnv,
    );
    assert.equal(response.status, 403);
    assert.equal(calls, 0);
  }
});
test("HTTPS keeps the original Secure session cookie even with local mode selected", async () => {
  const cookie =
    "__Host-hotel-session=opaque; Secure; HttpOnly; Path=/; SameSite=Lax";
  const response = await proxyApi(
    post(),
    ["auth", "login"],
    async () =>
      Response.json(
        {},
        {
          headers: { "Set-Cookie": cookie },
        },
      ),
    { ...env, LOCAL_HTTP_MODE: "1" },
  );
  assert.equal(response.status, 200);
  assert.deepEqual(response.headers.getSetCookie(), [cookie]);
});

test("admin paths keep Origin/CSRF checks and bound multipart uploads separately", async () => {
  let calls = 0;
  const fetcher: typeof fetch = async (_, init) => {
    calls++;
    assert.equal(new Headers(init?.headers).get("X-CSRF-TOKEN"), "csrf");
    return Response.json({ url: "/media/example.png" }, { status: 201 });
  };
  const req = (origin: string, body: string) =>
    new Request("https://hotel.example/api/v2/admin/media", {
      method: "POST",
      headers: {
        Origin: origin,
        "X-CSRF-TOKEN": "csrf",
        "Content-Type": "multipart/form-data; boundary=sample",
      },
      body,
    });
  assert.equal(
    (
      await proxyApi(
        req("https://evil.test", "x"),
        ["admin", "media"],
        fetcher,
        env,
      )
    ).status,
    403,
  );
  assert.equal(calls, 0);
  assert.equal(
    (
      await proxyApi(
        req("https://hotel.example", "x".repeat(20000)),
        ["admin", "media"],
        fetcher,
        env,
      )
    ).status,
    201,
  );
  assert.equal(
    (
      await proxyApi(
        req("https://hotel.example", "x".repeat(6 * 1024 * 1024 + 1)),
        ["admin", "media"],
        fetcher,
        env,
      )
    ).status,
    413,
  );
  assert.equal(calls, 1);
});
