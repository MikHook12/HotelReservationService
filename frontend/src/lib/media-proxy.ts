import { backendOrigin } from "./backend-config";
const filenamePattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(?:jpg|png)$/;
export async function proxyMedia(
  filename: string,
  fetcher: typeof fetch = fetch,
  env: NodeJS.ProcessEnv = process.env,
): Promise<Response> {
  if (!filenamePattern.test(filename))
    return new Response(null, { status: 404 });
  try {
    const response = await fetcher(
      new URL(`/api/v2/media/${filename}`, backendOrigin(env)),
      {
        headers: { Accept: "image/jpeg,image/png" },
        redirect: "manual",
        cache: "no-store",
        signal: AbortSignal.timeout(10000),
      },
    );
    if (response.status === 404) return new Response(null, { status: 404 });
    const type = response.headers.get("content-type")?.split(";")[0];
    if (
      response.status !== 200 ||
      !["image/jpeg", "image/png"].includes(type || "")
    )
      return new Response(null, { status: 502 });
    return new Response(response.body, {
      headers: {
        "Content-Type": type!,
        "Cache-Control": "public, max-age=86400, immutable",
        "X-Content-Type-Options": "nosniff",
      },
    });
  } catch {
    return new Response(null, { status: 503 });
  }
}
