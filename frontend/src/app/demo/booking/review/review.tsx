"use client";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { createApiClient, ApiClientError } from "@/lib/api-client";
import { BookingFlow, type ReviewState } from "@/lib/booking-flow";
import { reviewHref } from "@/lib/booking-selection";
import { rubles } from "@/lib/format";
import type { QuoteRequest } from "@/lib/types";
export function Review({ selection }: { selection: QuoteRequest }) {
  const router = useRouter(),
    flow = useRef<BookingFlow | null>(null);
  const [state, setState] = useState<ReviewState>({ phase: "loading" }),
    [consent, setConsent] = useState(false),
    [checking, setChecking] = useState(false),
    [fenceConfirm, setFenceConfirm] = useState(false);
  const autoStart = useRef<number | null>(null);
  const returnTo = reviewHref(selection);
  useEffect(() => {
    const controller = new AbortController(),
      client = createApiClient();
    let current: BookingFlow | null = null;
    setState({ phase: "loading" });
    setConsent(false);
    autoStart.current = null;
    client
      .me(controller.signal)
      .then((session) => {
        if (controller.signal.aborted) return;
        let storage: Storage | null = null;
        try {
          storage = window.sessionStorage;
        } catch {}
        current = new BookingFlow(
          client,
          selection,
          session.user.id,
          storage,
          (next) => {
            setState(next);
            if (next.phase !== "ready") setConsent(false);
            if (next.phase === "succeeded" && next.receipt)
              router.replace(`/account/bookings/${next.receipt.bookingId}`);
          },
          () => crypto.randomUUID(),
          controller.signal,
        );
        flow.current = current;
        return current.start();
      })
      .catch((e) => {
        if (!controller.signal.aborted) {
          if (e instanceof ApiClientError && e.status === 401)
            router.replace(`/login?returnTo=${encodeURIComponent(returnTo)}`);
          else
            setState({
              phase: "error",
              message:
                e instanceof ApiClientError
                  ? e.message
                  : "Не удалось проверить вход.",
            });
        }
      });
    return () => {
      current?.dispose();
      controller.abort();
      flow.current = null;
    };
  }, [returnTo, router]); // returnTo is the canonical selection; never includes a token or operation key.
  useEffect(() => {
    if (state.phase !== "processing") return;
    autoStart.current ??= Date.now();
    if (Date.now() - autoStart.current >= 30000) return;
    const timer = setTimeout(() => void flow.current?.check(), 2000);
    return () => clearTimeout(timer);
  }, [state]);
  async function check() {
    if (checking) return;
    setChecking(true);
    try {
      await flow.current?.check();
    } finally {
      setChecking(false);
    }
  }
  async function fence() {
    if (checking) return;
    setChecking(true);
    setFenceConfirm(false);
    try {
      await flow.current?.fence();
    } finally {
      setChecking(false);
    }
  }
  const quote = state.quote;
  return (
    <div className="stack booking-review">
      <header className="page-intro">
        <p className="eyebrow">Последний шаг</p>
        <h1>Проверьте своё путешествие</h1>
        <p>
          Подтверждение создаст демонстрационную бронь. Оплаты и реального
          заселения нет.
        </p>
      </header>
      {["loading", "quoting"].includes(state.phase) && (
        <p className="panel" role="status">
          Проверяем вход, доступность и условия…
        </p>
      )}
      {quote && (
        <div className="review-grid">
          <section className="panel stack">
            <h2>{quote.snapshot.hotelName}</h2>
            <h3>{quote.snapshot.roomName}</h3>
            <p>{quote.snapshot.hotelAddress}</p>
            <dl className="booking-facts">
              <div>
                <dt>Заезд</dt>
                <dd>{quote.stay.checkInDate}</dd>
              </div>
              <div>
                <dt>Выезд</dt>
                <dd>{quote.stay.checkOutDate}</dd>
              </div>
              <div>
                <dt>Гости</dt>
                <dd>{quote.stay.guests}</dd>
              </div>
              <div>
                <dt>Часовой пояс</dt>
                <dd>{quote.snapshot.timeZone}</dd>
              </div>
            </dl>
            <p>
              Отмена до{" "}
              {new Date(quote.cancellationDeadlineAt).toLocaleString("ru-RU", {
                timeZone: quote.snapshot.timeZone,
              })}{" "}
              ({quote.snapshot.timeZone}).
            </p>
          </section>
          <section className="panel stack">
            <h2>Итог расчёта</h2>
            <p>
              {rubles(quote.pricing.nightlyPrice)} × {quote.pricing.nights}{" "}
              ночей
            </p>
            <p className="booking-total">{rubles(quote.pricing.totalPrice)}</p>
            <p>Все сборы включены. Оплата не требуется.</p>
            <p className="muted">
              Расчёт не удерживает комнату. При изменении условий потребуется
              новый расчёт и ваше согласие.
            </p>
            {state.phase === "ready" && (
              <>
                <label className="consent">
                  <input
                    type="checkbox"
                    checked={consent}
                    onChange={(e) => setConsent(e.target.checked)}
                  />
                  Я проверил даты, итоговую стоимость и правила отмены.
                  Подтверждаю демонстрационное бронирование.
                </label>
                <button
                  className="button"
                  disabled={!consent}
                  onClick={() => void flow.current?.confirm(consent)}
                >
                  Подтвердить демобронирование
                </button>
              </>
            )}
            {state.phase === "submitting" && (
              <p role="status">
                Отправляем подтверждение. Не закрывайте страницу…
              </p>
            )}
          </section>
        </div>
      )}
      {state.message && (
        <section
          className="panel stack"
          role={state.phase === "error" ? "alert" : "status"}
        >
          <h2>
            {state.phase === "failed"
              ? "Бронь не создана"
              : state.phase === "error"
                ? "Не удалось продолжить"
                : "Проверяем результат"}
          </h2>
          <p>{state.message}</p>
        </section>
      )}
      {["unknown", "processing"].includes(state.phase) && (
        <section className="panel stack">
          <p>
            Не создавайте новый запрос, пока не проверен исходный. Ожидание само
            по себе не означает отказ.
          </p>
          <button
            className="button"
            disabled={checking}
            onClick={() => void check()}
          >
            {checking ? "Проверяем…" : "Проверить результат"}
          </button>
          <button
            className="button secondary"
            disabled={checking}
            onClick={() => setFenceConfirm(true)}
          >
            Закрыть ещё не принятый запрос
          </button>
          {fenceConfirm && (
            <div className="stack">
              <p>
                Сервер закроет запрос, только если он ещё не принят. Уже
                созданная бронь сохранится. Продолжить?
              </p>
              <button
                className="button secondary"
                disabled={checking}
                onClick={() => void fence()}
              >
                Да, проверить и закрыть
              </button>
              <button
                className="button secondary"
                onClick={() => setFenceConfirm(false)}
              >
                Назад
              </button>
            </div>
          )}
          <p className="muted">
            Проверка требует входа в тот же аккаунт. При истечении сессии
            войдите снова; ключ операции останется только в этой вкладке.
          </p>
          <Link href={`/login?returnTo=${encodeURIComponent(returnTo)}`}>
            Войти снова
          </Link>
        </section>
      )}
      {["failed", "error"].includes(state.phase) &&
        flow.current?.canPrepare && (
          <button
            className="button"
            onClick={() => void flow.current?.prepare()}
          >
            Получить новый расчёт
          </button>
        )}
      <Link className="button secondary" href="/account/bookings">
        Проверить свои брони
      </Link>
      {state.phase === "error" && (
        <Link href={`/login?returnTo=${encodeURIComponent(returnTo)}`}>
          Войти снова
        </Link>
      )}
      <p className="muted">
        Если вы уже нажимали подтверждение и затем очищали хранилище браузера,
        сначала проверьте свои брони в кабинете.
      </p>
    </div>
  );
}
