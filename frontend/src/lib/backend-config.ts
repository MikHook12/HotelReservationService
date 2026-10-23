export function backendOrigin(env: NodeJS.ProcessEnv = process.env): URL {
  const url = new URL(env.BROWSER_BACKEND_ORIGIN || "http://127.0.0.1:8080");
  if (
    !["http:", "https:"].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.pathname !== "/" ||
    url.search ||
    url.hash
  )
    throw new Error("Invalid backend origin configuration");
  return url;
}
export function publicOrigin(env: NodeJS.ProcessEnv = process.env): URL {
  const url = new URL(env.PUBLIC_ORIGIN || "https://localhost:3000");
  const loopback = (u: URL) =>
    ["127.0.0.1", "localhost", "[::1]"].includes(u.hostname);
  const local =
    env.NODE_ENV !== "production" &&
    (env.FRONTEND_QA_MODE === "1" || env.LOCAL_HTTP_MODE === "1") &&
    loopback(url) &&
    loopback(backendOrigin(env));
  if (
    (url.protocol !== "https:" && !(local && url.protocol === "http:")) ||
    url.username ||
    url.password ||
    url.pathname !== "/" ||
    url.search ||
    url.hash
  )
    throw new Error("PUBLIC_ORIGIN must be an HTTPS origin");
  return url;
}
export function localHttpMode(env: NodeJS.ProcessEnv = process.env): boolean {
  // publicOrigin also rejects production and non-loopback HTTP configurations.
  return env.LOCAL_HTTP_MODE === "1" && publicOrigin(env).protocol === "http:";
}
export function qaMode() {
  return (
    process.env.NODE_ENV !== "production" &&
    process.env.FRONTEND_QA_MODE === "1"
  );
}
