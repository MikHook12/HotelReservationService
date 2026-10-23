import { test } from "node:test";
import assert from "node:assert/strict";
import { proxyMedia } from "../src/lib/media-proxy";
const file = "fa503f40-b152-48d2-9724-b49acb959672.png";
test("public media proxy serves only fixed-origin UUID images without browser credentials", async () => {
  const response = await proxyMedia(file, async (url, init) => {
    assert.equal(String(url), `http://127.0.0.1:8080/api/v2/media/${file}`);
    assert.equal(new Headers(init?.headers).get("Cookie"), null);
    return new Response(new Uint8Array([1, 2]), {
      headers: { "Content-Type": "image/png", "Set-Cookie": "private=never" },
    });
  });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("Set-Cookie"), null);
  assert.equal(response.headers.get("X-Content-Type-Options"), "nosniff");
  let calls = 0;
  for (const bad of [
    "../credentials.json",
    "https://evil.test/a.png",
    "bad.svg",
    "x.png",
  ]) {
    assert.equal(
      (
        await proxyMedia(bad, async () => {
          calls++;
          return new Response();
        })
      ).status,
      404,
    );
  }
  assert.equal(calls, 0);
});
test("public media hides redirects and non-image upstream responses", async () => {
  for (const result of [
    new Response("private", {
      status: 200,
      headers: { "Content-Type": "text/html" },
    }),
    new Response(null, {
      status: 302,
      headers: { Location: "https://evil.test" },
    }),
  ]) {
    const response = await proxyMedia(file, async () => result);
    assert.equal(response.status, 502);
    assert.equal(await response.text(), "");
  }
});
