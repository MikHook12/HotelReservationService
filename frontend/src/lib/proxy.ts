import { proxyMedia } from "./media-proxy";
import { backendOrigin, publicOrigin, localHttpMode } from "./backend-config";
const upstreamSession = "__Host-hotel-session";
const localSession = "hotel-local-session";

function requestCookie(value: string): string {
  return value
    .split(";")
    .map((part) => part.trim())
    .filter((part) => !part.startsWith(`${upstreamSession}=`))
    .map((part) =>
      part.startsWith(`${localSession}=`)
        ? `${upstreamSession}=${part.slice(localSession.length + 1)}`
        : part,
    )
    .join("; ");
}
function responseCookie(value: string): string {
  if (!value.startsWith(`${upstreamSession}=`)) return value;
  return `${localSession}=${value.slice(upstreamSession.length + 1)}`.replace(
    /;\s*Secure(?=;|$)/gi,
    "",
  );
}
const reads =
  /^(?:cities|hotels(?:\/[a-z0-9-]+(?:\/rooms)?)?|rooms\/[1-9]\d*|auth\/(?:csrf|me)|admin\/hotels(?:\/[1-9]\d*(?:\/rooms)?)?|me\/bookings(?:\/[1-9]\d*)?|me\/booking-operations\/result)$/;
const writes =
  /^(?:admin\/(?:hotels(?:\/[1-9]\d*(?:\/rooms)?)?|rooms\/[1-9]\d*|media)|auth\/(?:login|logout)|bookings(?:\/quote)?|me\/bookings\/[1-9]\d*\/cancel|me\/booking-operations\/fence)$/;
function failure(status: number, code: string, message: string) {
  const traceId = crypto.randomUUID().replaceAll("-", "");
  return Response.json(
    { status, code, message, fieldErrors: [], details: {}, traceId },
    {
      status,
      headers: {
        "Cache-Control": "private, no-store",
        Vary: "Cookie",
        "X-Trace-Id": traceId,
      },
    },
  );
}
export async function proxyApi(
  request: Request,
  segments: string[],
  fetcher: typeof fetch = fetch,
  env: NodeJS.ProcessEnv = process.env,
): Promise<Response> {
  const path = segments.join("/");
  if (request.method === "GET" && segments.length === 2 && segments[0] === "media") return proxyMedia(segments[1], fetcher, env);
  const unsafe = request.method === "POST";
  const upload = unsafe && path === "admin/media";
  const limit = upload
    ? 6 * 1024 * 1024
    : path.startsWith("admin/")
      ? 131072
      : 16384;
  if (
    !(unsafe ? writes : reads).test(path) ||
    !["GET", "POST"].includes(request.method)
  )
    return failure(404, "NOT_FOUND", "Страница не найдена.");
  let upstream: URL;
  let localHttp = false;
  try {
    upstream = backendOrigin(env);
    const external = publicOrigin(env);
    localHttp = localHttpMode(env);
    if (unsafe && request.headers.get("origin") !== external.origin)
      return failure(
        403,
        "CSRF_INVALID",
        "Откройте сайт по настроенному адресу и повторите действие.",
      );
  } catch {
    return failure(503, "SERVICE_UNAVAILABLE", "Вход временно недоступен.");
  }
  const headers = new Headers({ Accept: "application/json" });
  for (const key of [
    "cookie",
    "content-type",
    "x-csrf-token",
    "idempotency-key",
  ]) {
    const v = request.headers.get(key);
    if (v)
      headers.set(key, key === "cookie" && localHttp ? requestCookie(v) : v);
  }
  // Validate the external browser Origin above before translating to the fixed internal origin.
  // Spring still independently validates the session-bound CSRF token; forwarding headers are not trusted.
  if (unsafe) headers.set("Origin", upstream.origin);
  let body: Uint8Array | undefined;
  if (unsafe) {
    const reader = request.body?.getReader();
    const chunks: Uint8Array[] = [];
    let size = 0;
    if (reader)
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.length;
        if (size > limit) {
          await reader.cancel();
          return failure(413, "PAYLOAD_TOO_LARGE", "Слишком большой запрос.");
        }
        chunks.push(value);
      }
    if (size) {
      body = new Uint8Array(size);
      let at = 0;
      for (const chunk of chunks) {
        body.set(chunk, at);
        at += chunk.length;
      }
    }
  }
  try {
    const url = new URL(`/api/v2/${path}`, upstream);
    url.search = new URL(request.url).search;
    const response = await fetcher(url, {
      method: request.method,
      headers,
      body: body as BodyInit | undefined,
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.timeout(10000),
    });
    if (response.status >= 300 && response.status < 400)
      return failure(502, "SERVICE_UNAVAILABLE", "Сервис временно недоступен.");
    if (
      response.status !== 204 &&
      !response.headers.get("content-type")?.includes("application/json")
    )
      return failure(502, "SERVICE_UNAVAILABLE", "Сервис временно недоступен.");
    const outgoing = new Headers({
      "Cache-Control": "private, no-store",
      Vary: "Cookie",
      "X-Content-Type-Options": "nosniff",
    });
    for (const key of ["content-type", "x-trace-id", "retry-after"]) {
      const v = response.headers.get(key);
      if (v) outgoing.set(key, v);
    }
    for (const cookie of response.headers.getSetCookie())
      outgoing.append(
        "set-cookie",
        localHttp ? responseCookie(cookie) : cookie,
      );
    return new Response(response.status === 204 ? null : response.body, {
      status: response.status,
      headers: outgoing,
    });
  } catch {
    return failure(
      503,
      "SERVICE_UNAVAILABLE",
      "Сервис временно недоступен. Попробуйте позже.",
    );
  }
}
