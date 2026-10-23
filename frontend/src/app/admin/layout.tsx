"use client";
import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ApiClientError, createApiClient } from "@/lib/api-client";

export default function AdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const api = useRef(createApiClient());
  const [state, setState] = useState<
    "loading" | "admin" | "anonymous" | "forbidden" | "error"
  >("loading");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    api.current
      .me(controller.signal)
      .then((session) =>
        setState(session.user.roles.includes("ADMIN") ? "admin" : "forbidden"),
      )
      .catch((e) => {
        if (!controller.signal.aborted)
          setState(
            e instanceof ApiClientError && e.status === 401
              ? "anonymous"
              : "error",
          );
      });
    return () => controller.abort();
  }, []);
  async function switchAccount() {
    if (busy) return;
    setBusy(true);
    try {
      await api.current.logout();
      window.location.assign("/login?returnTo=/admin");
    } catch {
      setState("error");
      setBusy(false);
    }
  }
  return (
    <div className="wrap admin-shell stack">
      <nav className="admin-nav" aria-label="Управление каталогом">
        <Link href="/admin">Отели и комнаты</Link>
        <Link href="/demo/hotels">Открыть каталог</Link>
        {state === "admin" && (
          <button
            className="button secondary"
            disabled={busy}
            onClick={switchAccount}
          >
            Выйти
          </button>
        )}
      </nav>
      {state === "loading" && <p role="status">Проверяем права доступа…</p>}
      {state === "anonymous" && (
        <section className="panel stack">
          <h1>Вход для администратора</h1>
          <p>
            Управление каталогом доступно только сотруднику с ролью
            администратора.
          </p>
          <Link className="button" href="/login?returnTo=/admin">
            Войти
          </Link>
        </section>
      )}
      {state === "forbidden" && (
        <section className="panel stack">
          <h1>Недостаточно прав</h1>
          <p>Ваш аккаунт не имеет роли администратора.</p>
          <button className="button" onClick={switchAccount} disabled={busy}>
            Выйти и сменить аккаунт
          </button>
        </section>
      )}
      {state === "error" && (
        <section className="panel stack" role="alert">
          <h1>Не удалось проверить доступ</h1>
          <p>Обновите страницу или войдите снова.</p>
          <Link href="/login?returnTo=/admin">Страница входа</Link>
        </section>
      )}
      {state === "admin" && children}
    </div>
  );
}
