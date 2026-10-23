"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { ApiClientError, createApiClient } from "@/lib/api-client";
import { Feedback } from "@/components/feedback";
import { PageIntro } from "@/components/page-intro";
import { BookingSummary } from "@/components/booking-summary";
import type { Booking, BookingPage, SessionInfo } from "@/lib/types";

type BookingStatus = "CONFIRMED" | "CANCELLED";
type ViewData =
  | { key: string; kind: "list"; session: SessionInfo; page: BookingPage }
  | { key: string; kind: "detail"; session: SessionInfo; booking: Booking };
type ViewIssue = {
  key: string;
  title: string;
  message: string;
  notFound?: boolean;
};
type CancelIssue = { message: string; unknownResult: boolean };

const PAGE_LIMIT = 1_000_000;

function formatPage(value: string | null): number {
  if (!value || !/^(0|[1-9][0-9]{0,6})$/.test(value)) return 0;
  const page = Number(value);
  return Number.isSafeInteger(page) && page <= PAGE_LIMIT ? page : 0;
}

function listHref(page: number, status?: BookingStatus): string {
  const params = new URLSearchParams();
  if (status) params.set("status", status);
  if (page > 0) params.set("page", String(page));
  const query = params.toString();
  return `/account/bookings${query ? `?${query}` : ""}`;
}

function loginHref(returnTo: string): string {
  return `/login?returnTo=${encodeURIComponent(returnTo)}`;
}

function apiMessage(error: unknown, fallback: string): string {
  return error instanceof ApiClientError ? error.message : fallback;
}

function deadlineText(booking: Booking): string {
  const { deadlineAt, timeZone } = booking.cancellation;
  if (!deadlineAt) return "Срок отмены не сохранён в записи.";

  const instant = new Date(deadlineAt);
  if (!Number.isFinite(instant.getTime()))
    return "Сервер вернул некорректный срок отмены.";
  const options: Intl.DateTimeFormatOptions = {
    day: "numeric",
    month: "long",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  };

  if (timeZone) {
    try {
      const local = new Intl.DateTimeFormat("ru-RU", {
        ...options,
        timeZone,
      }).format(instant);
      return `${local} (${timeZone})`;
    } catch {
      // Show the server instant with its explicit UTC zone if an IANA zone is malformed.
    }
  }

  const utc = new Intl.DateTimeFormat("ru-RU", {
    ...options,
    timeZone: "UTC",
  }).format(instant);
  return `${utc} UTC · часовой пояс отеля не указан`;
}

function cancellationReason(booking: Booking): string {
  if (booking.cancellation.reason === "DEADLINE_PASSED")
    return "Срок отмены истёк по времени отеля.";
  if (booking.cancellation.reason === "LEGACY_TIMEZONE_UNKNOWN")
    return "Эту старую бронь нельзя отменить: часовой пояс отеля не подтверждён.";
  if (
    booking.cancellation.reason === "ALREADY_CANCELLED" ||
    booking.status === "CANCELLED"
  )
    return "Бронь уже отменена. Запись сохранена в истории.";
  return "Сервер сейчас не разрешает отмену этой брони.";
}

export function Account() {
  const client = useRef(createApiClient());
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const normalizedPath = pathname.replace(/\/+$/, "") || "/";
  const detailMatch = normalizedPath.match(
    /^\/account\/bookings\/([1-9][0-9]*)$/,
  );
  const bookingId = detailMatch?.[1] ?? null;
  const isList = normalizedPath === "/account/bookings";
  const invalidRoute = !isList && !bookingId;
  const statusValues = searchParams.getAll("status");
  const statusValue = statusValues[0];
  const status: BookingStatus | undefined =
    statusValues.length === 1 &&
    (statusValue === "CONFIRMED" || statusValue === "CANCELLED")
      ? statusValue
      : undefined;
  const pageValues = searchParams.getAll("page");
  const page = formatPage(pageValues.length === 1 ? pageValues[0] : null);
  const routeQuery = useMemo(() => {
    const params = new URLSearchParams();
    if (status) params.set("status", status);
    if (page > 0) params.set("page", String(page));
    return params.toString();
  }, [page, status]);
  const viewKey = `${normalizedPath}?${routeQuery}`;

  const [loaded, setLoaded] = useState<ViewData | null>(null);
  const [loadIssue, setLoadIssue] = useState<ViewIssue | null>(null);
  const [loading, setLoading] = useState(true);
  const [refresh, setRefresh] = useState(0);
  const [logoutBusy, setLogoutBusy] = useState(false);
  const [logoutIssue, setLogoutIssue] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [cancelBusy, setCancelBusy] = useState(false);
  const [cancelIssue, setCancelIssue] = useState<CancelIssue | null>(null);
  const owner = useRef<string | null>(null);
  const activeLoad = useRef<AbortController | null>(null);
  const activeCancel = useRef<AbortController | null>(null);
  const cancelLocked = useRef(false);
  const logoutLocked = useRef(false);
  const confirmationHeading = useRef<HTMLHeadingElement>(null);

  const returnTo = bookingId
    ? `/account/bookings/${bookingId}`
    : "/account/bookings";
  const loadedView = loaded?.key === viewKey ? loaded : null;
  const currentIssue = loadIssue?.key === viewKey ? loadIssue : null;
  const currentBooking =
    loadedView?.kind === "detail" ? loadedView.booking : null;

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    activeLoad.current = controller;
    setLoading(true);
    setLoadIssue(null);

    if (invalidRoute) {
      setLoadIssue({
        key: viewKey,
        title: "Бронь не найдена",
        message: "Не удалось найти запрошенную запись.",
        notFound: true,
      });
      setLoading(false);
      return () => {
        active = false;
        controller.abort();
      };
    }

    async function loadView() {
      try {
        const session = await client.current.me(controller.signal);
        if (!active || controller.signal.aborted) return;
        if (owner.current && owner.current !== session.user.id) setLoaded(null);
        owner.current = session.user.id;

        if (bookingId) {
          const booking = await client.current.ownBooking(
            bookingId,
            controller.signal,
          );
          if (!active || controller.signal.aborted) return;
          setLoaded({ key: viewKey, kind: "detail", session, booking });
          setCancelIssue(null);
        } else {
          const result = await client.current.ownBookings(
            { page, ...(status ? { status } : {}) },
            controller.signal,
          );
          if (!active || controller.signal.aborted) return;
          setLoaded({ key: viewKey, kind: "list", session, page: result });
        }
        setLogoutIssue(null);
      } catch (error) {
        if (!active || controller.signal.aborted) return;
        if (error instanceof ApiClientError && error.status === 401) {
          setLoaded(null);
          router.replace(loginHref(returnTo));
          router.refresh();
          return;
        }
        const notFound = Boolean(
          bookingId && error instanceof ApiClientError && error.status === 404,
        );
        setLoadIssue({
          key: viewKey,
          title: notFound ? "Бронь не найдена" : "Не удалось загрузить данные",
          message: notFound
            ? "Запись не найдена. Проверьте список своих броней."
            : apiMessage(
                error,
                "Не удалось связаться с сервисом. Повторите загрузку.",
              ),
          notFound,
        });
      } finally {
        if (active && !controller.signal.aborted) setLoading(false);
      }
    }

    void loadView();
    return () => {
      active = false;
      controller.abort();
      if (activeLoad.current === controller) activeLoad.current = null;
      activeCancel.current?.abort();
      activeCancel.current = null;
      cancelLocked.current = false;
    };
  }, [
    bookingId,
    invalidRoute,
    page,
    refresh,
    returnTo,
    router,
    status,
    viewKey,
  ]);

  useEffect(() => {
    setConfirming(false);
    setCancelBusy(false);
    setCancelIssue(null);
  }, [viewKey]);

  useEffect(() => {
    if (confirming) confirmationHeading.current?.focus();
  }, [confirming]);

  async function logout() {
    if (logoutLocked.current) return;
    logoutLocked.current = true;
    setLogoutBusy(true);
    setLogoutIssue(null);
    activeLoad.current?.abort();
    activeCancel.current?.abort();
    activeCancel.current = null;
    cancelLocked.current = false;
    setCancelBusy(false);
    setLoading(false);
    const controller = new AbortController();
    try {
      await client.current.logout(controller.signal);
      setLoaded(null);
      setLoadIssue(null);
      setCancelIssue(null);
      router.replace("/login");
      router.refresh();
    } catch (error) {
      if (error instanceof ApiClientError && error.status === 401) {
        setLoaded(null);
        router.replace("/login");
        router.refresh();
      } else {
        setLogoutIssue(
          apiMessage(error, "Не удалось выйти. Повторите действие."),
        );
      }
    } finally {
      logoutLocked.current = false;
      setLogoutBusy(false);
    }
  }

  async function cancelBooking(retryUnknown = false) {
    const detailView = loadedView;
    if (
      !detailView ||
      detailView.kind !== "detail" ||
      !currentBooking ||
      cancelLocked.current ||
      cancelBusy
    )
      return;
    if (
      currentBooking.status !== "CONFIRMED" ||
      (!currentBooking.cancellation.canCancel && !retryUnknown)
    )
      return;

    cancelLocked.current = true;
    setCancelBusy(true);
    setCancelIssue(null);
    const controller = new AbortController();
    activeCancel.current = controller;
    try {
      const updated = await client.current.cancelBooking(
        currentBooking.id,
        controller.signal,
      );
      if (controller.signal.aborted) return;
      setLoaded({
        key: viewKey,
        kind: "detail",
        session: detailView.session,
        booking: updated,
      });
      setConfirming(false);
      setCancelIssue(null);
      // Read the current owner view after the idempotent server transition.
      setRefresh((value) => value + 1);
    } catch (error) {
      if (controller.signal.aborted) return;
      if (error instanceof ApiClientError && error.status === 401) {
        setLoaded(null);
        router.replace(loginHref(returnTo));
        router.refresh();
        return;
      }
      const unknownResult =
        !(error instanceof ApiClientError) ||
        error.status === 0 ||
        error.status >= 500;
      const message =
        error instanceof ApiClientError && error.status === 404
          ? "Бронь не найдена. Проверьте список своих броней."
          : apiMessage(
              error,
              "Ответ не получен. Статус отмены нужно проверить.",
            );
      setCancelIssue({ message, unknownResult });
      setConfirming(unknownResult);
      if (!unknownResult) setRefresh((value) => value + 1);
    } finally {
      if (!controller.signal.aborted) {
        cancelLocked.current = false;
        activeCancel.current = null;
        setCancelBusy(false);
      }
    }
  }

  function retryLoad() {
    setLoadIssue(null);
    setRefresh((value) => value + 1);
  }

  if (invalidRoute) {
    return (
      <section className="stack">
        <PageIntro eyebrow="Личный кабинет" title="Бронь не найдена" />
        <Feedback title="Бронь не найдена" tone="error">
          <p>Запись не найдена. Проверьте список своих броней.</p>
        </Feedback>
        <Link className="button secondary" href="/account/bookings">
          К моим броням
        </Link>
      </section>
    );
  }

  if (!loadedView && !currentIssue) {
    return (
      <section className="stack" aria-busy="true">
        <PageIntro
          eyebrow="Личный кабинет"
          title={bookingId ? "Загружаем бронь…" : "Загружаем ваши брони…"}
          description="Проверяем вашу сессию и загружаем данные из личного раздела."
        />
        <div className="panel stack" role="status" aria-live="polite">
          <p className="muted">
            {loading ? "Подождите немного…" : "Данные пока не загружены."}
          </p>
        </div>
      </section>
    );
  }

  if (currentIssue && !loadedView) {
    return (
      <section className="stack">
        <PageIntro eyebrow="Личный кабинет" title={currentIssue.title} />
        <Feedback title={currentIssue.title} tone="error">
          <p>{currentIssue.message}</p>
        </Feedback>
        <div className="form-actions">
          {currentIssue.notFound ? (
            <Link className="button secondary" href="/account/bookings">
              К моим броням
            </Link>
          ) : (
            <button className="button" type="button" onClick={retryLoad}>
              Повторить загрузку
            </button>
          )}
          <Link className="button secondary" href="/demo/hotels">
            В каталог
          </Link>
        </div>
      </section>
    );
  }

  if (loadedView?.kind === "list") {
    const currentStatus = status;
    const rows = loadedView.page.items;
    const totalPages = loadedView.page.totalPages;
    const outOfRange = rows.length === 0 && page > 0 && page >= totalPages;

    return (
      <section className="stack">
        <div className="page-intro">
          <p className="eyebrow">Личный кабинет</p>
          <h1>Ваши брони</h1>
          <p className="page-intro-description">
            Здравствуйте, {loadedView.session.user.username}. Здесь показаны
            записи, связанные с вашим аккаунтом.
          </p>
        </div>

        {logoutIssue ? (
          <Feedback title="Не удалось выйти" tone="error">
            <p>{logoutIssue}</p>
          </Feedback>
        ) : null}
        <div className="form-actions">
          <Link className="button secondary" href="/demo/hotels">
            Найти отель
          </Link>
          <button
            className="button secondary"
            type="button"
            onClick={logout}
            disabled={logoutBusy}
          >
            {logoutBusy ? "Выходим…" : "Выйти из аккаунта"}
          </button>
        </div>

        <nav className="breadcrumb" aria-label="Фильтр броней">
          <Link
            href={listHref(0)}
            aria-current={!currentStatus ? "page" : undefined}
          >
            Все
          </Link>
          <span aria-hidden="true">·</span>
          <Link
            href={listHref(0, "CONFIRMED")}
            aria-current={currentStatus === "CONFIRMED" ? "page" : undefined}
          >
            Подтверждённые
          </Link>
          <span aria-hidden="true">·</span>
          <Link
            href={listHref(0, "CANCELLED")}
            aria-current={currentStatus === "CANCELLED" ? "page" : undefined}
          >
            Отменённые
          </Link>
        </nav>

        {currentIssue ? (
          <Feedback title={currentIssue.title} tone="error">
            <p>{currentIssue.message}</p>
            <button
              className="button secondary"
              type="button"
              onClick={retryLoad}
            >
              Повторить загрузку
            </button>
          </Feedback>
        ) : null}
        {loading ? (
          <p className="muted" role="status">
            Обновляем список…
          </p>
        ) : null}
        {outOfRange ? (
          <Feedback title="Страница списка изменилась">
            <p>
              На этой странице больше нет записей. Откройте первую страницу
              списка.
            </p>
            <Link href={listHref(0, currentStatus)}>Первая страница →</Link>
          </Feedback>
        ) : rows.length > 0 ? (
          <>
            <p className="muted">
              Найдено записей: {loadedView.page.totalElements}
            </p>
            <div className="stack" role="list" aria-label="Список ваших броней">
              {rows.map((booking) => (
                <BookingSummary
                  key={booking.id}
                  booking={booking}
                  compact
                  href={`/account/bookings/${booking.id}`}
                />
              ))}
            </div>
            {totalPages > 1 ? (
              <nav className="form-actions" aria-label="Страницы списка броней">
                {page > 0 ? (
                  <Link
                    className="button secondary"
                    href={listHref(page - 1, currentStatus)}
                  >
                    ← Назад
                  </Link>
                ) : null}
                <span className="badge" aria-current="page">
                  Страница {page + 1} из {totalPages}
                </span>
                {page + 1 < totalPages ? (
                  <Link
                    className="button secondary"
                    href={listHref(page + 1, currentStatus)}
                  >
                    Дальше →
                  </Link>
                ) : null}
              </nav>
            ) : null}
          </>
        ) : (
          <Feedback
            title={
              currentStatus ? "В этой категории броней нет" : "Пока нет броней"
            }
          >
            <p>
              {currentStatus
                ? "Выберите другой фильтр, чтобы посмотреть остальные записи."
                : "Когда вы подтвердите бронь, её статус и условия появятся здесь."}
            </p>
            <Link href="/demo/hotels">Открыть каталог →</Link>
          </Feedback>
        )}
      </section>
    );
  }

  if (!loadedView || loadedView.kind !== "detail" || !currentBooking)
    return null;
  const booking = currentBooking;
  const canCancel =
    booking.status === "CONFIRMED" && booking.cancellation.canCancel;
  const reason = cancellationReason(booking);

  return (
    <section className="stack">
      <nav className="breadcrumb" aria-label="Навигация по кабинету">
        <Link href="/account/bookings">Мои брони</Link>
        <span aria-hidden="true">›</span>
        <span aria-current="page">Бронь № {booking.id}</span>
      </nav>
      <PageIntro
        eyebrow="Личный кабинет · детали брони"
        title={
          booking.status === "CANCELLED" ? "Бронь отменена" : "Детали брони"
        }
        description={
          booking.status === "CANCELLED"
            ? "Запись осталась в истории. Новая поездка потребует отдельной брони."
            : "Ниже показаны сохранённые сведения и текущее решение сервера об отмене."
        }
      />
      {currentIssue ? (
        <Feedback title={currentIssue.title} tone="error">
          <p>{currentIssue.message}</p>
          <button
            className="button secondary"
            type="button"
            onClick={retryLoad}
          >
            Повторить загрузку
          </button>
        </Feedback>
      ) : null}
      {loading ? (
        <p className="muted" role="status">
          Обновляем сведения…
        </p>
      ) : null}
      {cancelIssue ? (
        <Feedback
          title={
            cancelIssue.unknownResult
              ? "Результат отмены пока неизвестен"
              : "Не удалось отменить бронь"
          }
          tone="error"
        >
          <p>{cancelIssue.message}</p>
          {cancelIssue.unknownResult ? (
            <p>
              Повторная отмена безопасна: сервер выполняет этот переход
              идемпотентно и вернёт текущее состояние.
            </p>
          ) : null}
        </Feedback>
      ) : null}
      {logoutIssue ? (
        <Feedback title="Не удалось выйти" tone="error">
          <p>{logoutIssue}</p>
        </Feedback>
      ) : null}

      <BookingSummary booking={booking} />

      <section
        className="panel stack"
        aria-labelledby="cancellation-heading"
        aria-busy={cancelBusy}
      >
        <h2 id="cancellation-heading">Отмена брони</h2>
        {booking.status === "CANCELLED" ? (
          <p>
            Бронь отменена. История сохранена, восстановить отменённую запись
            нельзя.
          </p>
        ) : canCancel ? (
          <>
            <p>
              Сервер разрешает отмену до{" "}
              <strong>{deadlineText(booking)}</strong>.
            </p>
            <p className="muted">
              Дедлайн и права проверяются сервером в сохранённом часовом поясе.
              Оплаты и возврата денег нет.
            </p>
            {!confirming ? (
              <button
                className="button secondary"
                type="button"
                onClick={() => {
                  setCancelIssue(null);
                  setConfirming(true);
                }}
                disabled={cancelBusy || loading}
              >
                Отменить бронь
              </button>
            ) : (
              <div
                className="panel stack"
                role="group"
                aria-labelledby="confirm-cancel-heading"
              >
                <h3
                  id="confirm-cancel-heading"
                  ref={confirmationHeading}
                  tabIndex={-1}
                >
                  Подтвердите отмену
                </h3>
                <p>
                  Отменить бронь № {booking.id}? После подтверждения запись
                  останется в истории, а сервер обновит её статус.
                </p>
                <div className="form-actions">
                  <button
                    className="button"
                    type="button"
                    onClick={() =>
                      void cancelBooking(cancelIssue?.unknownResult === true)
                    }
                    disabled={cancelBusy}
                  >
                    {cancelBusy
                      ? "Отменяем…"
                      : cancelIssue?.unknownResult
                        ? "Повторить отмену и проверить статус"
                        : "Да, отменить бронь"}
                  </button>
                  {!cancelIssue?.unknownResult ? (
                    <button
                      className="button secondary"
                      type="button"
                      onClick={() => setConfirming(false)}
                      disabled={cancelBusy || loading}
                    >
                      Оставить бронь
                    </button>
                  ) : null}
                </div>
              </div>
            )}
          </>
        ) : (
          <>
            <p>{reason}</p>
            {booking.cancellation.deadlineAt ? (
              <p>
                Срок, рассчитанный сервером:{" "}
                <strong>{deadlineText(booking)}</strong>.
              </p>
            ) : null}
          </>
        )}
      </section>

      <div className="form-actions">
        <Link className="button secondary" href="/account/bookings">
          К моим броням
        </Link>
        <button
          className="button secondary"
          type="button"
          onClick={logout}
          disabled={logoutBusy}
        >
          {logoutBusy ? "Выходим…" : "Выйти из аккаунта"}
        </button>
      </div>
    </section>
  );
}
