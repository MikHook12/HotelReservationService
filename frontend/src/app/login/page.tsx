import type { Metadata } from "next";
import { LoginForm } from "./login-form";
import { safeReturnTo } from "@/lib/navigation";
export const metadata: Metadata = {
  title: "Вход",
  robots: { index: false, follow: false },
};
export const dynamic = "force-dynamic";
export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const q = await searchParams;
  return (
    <div className="wrap auth-shell">
      <LoginForm
        returnTo={safeReturnTo(
          typeof q.returnTo === "string" ? q.returnTo : null,
        )}
      />
    </div>
  );
}
