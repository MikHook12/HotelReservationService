import "server-only";
import { backendOrigin } from "./backend-config";
export class BackendError extends Error {
  constructor(
    public status: number,
    public code: string,
    public traceId?: string,
  ) {
    super(code);
  }
}
export async function publicGet<T>(path: string): Promise<T> {
  if (!/^\/(?:cities|hotels(?:\/[a-z0-9-]+(?:\/rooms)?)?)(?:\?|$)/.test(path))
    throw new Error("Public API path rejected");
  try {
    const response = await fetch(new URL(`/api/v2${path}`, backendOrigin()), {
      cache: "no-store",
      signal: AbortSignal.timeout(8000),
      headers: { Accept: "application/json" },
      redirect: "error",
    });
    if (!response.ok) {
      const error = await response.json().catch(() => null);
      throw new BackendError(
        response.status,
        typeof error?.code === "string" ? error.code : "SERVICE_UNAVAILABLE",
        response.headers.get("x-trace-id") || undefined,
      );
    }
    return (await response.json()) as T;
  } catch (error) {
    if (error instanceof BackendError) throw error;
    throw new BackendError(503, "SERVICE_UNAVAILABLE");
  }
}
