import { proxyMedia } from "@/lib/media-proxy";
export const runtime = "nodejs";
export async function GET(
  _request: Request,
  context: { params: Promise<{ filename: string }> },
) {
  return proxyMedia((await context.params).filename);
}
