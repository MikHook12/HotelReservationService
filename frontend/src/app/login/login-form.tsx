"use client";
import { useState, useRef, useEffect } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { createApiClient, ApiClientError } from "@/lib/api-client";
export function LoginForm({ returnTo }: { returnTo: string }) {
  const router = useRouter();
  const client = useRef(createApiClient());
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<string | null>(null);
  const active = useRef<AbortController | null>(null);
  useEffect(() => () => active.current?.abort(), []);
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    const data = new FormData(event.currentTarget),
      password = String(data.get("password") || "");
    setBusy(true);
    setError(null);
    const controller = new AbortController();
    active.current = controller;
    try {
      await client.current.login(
        { username: String(data.get("username") || ""), password },
        controller.signal,
      );
      router.replace(returnTo);
      router.refresh();
    } catch (e) {
      if (!controller.signal.aborted)
        setError(
          e instanceof ApiClientError
            ? e.message
            : "Не удалось войти. Попробуйте ещё раз.",
        );
    } finally {
      if (!controller.signal.aborted) setBusy(false);
    }
  }
  return (
    <section className="panel auth-card stack">
      <p className="eyebrow">С возвращением</p>
      <h1>Войти в аккаунт</h1>
      <p className="muted">
        Используйте имя пользователя и пароль. После входа вернём вас к
        выбранной странице.
      </p>
      <form onSubmit={submit} className="stack" aria-busy={busy}>
        <label className="field">
          Имя пользователя
          <input
            name="username"
            autoComplete="username"
            required
            maxLength={50}
            disabled={busy}
          />
        </label>
        <label className="field">
          Пароль
          <input
            name="password"
            type="password"
            autoComplete="current-password"
            required
            maxLength={72}
            disabled={busy}
          />
        </label>
        {error && (
          <p className="feedback error" role="alert">
            {error}
          </p>
        )}
        <button className="button" disabled={busy} type="submit">
          {busy ? "Входим…" : "Войти"}
        </button>
      </form>
      <Link href="/register">Почему регистрация закрыта?</Link>
      <Link href="/demo/hotels">← Продолжить без входа</Link>
    </section>
  );
}
