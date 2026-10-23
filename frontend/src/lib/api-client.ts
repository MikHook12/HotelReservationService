import type {
  ApiError,
  Booking,
  BookingCreationReceipt,
  BookingPage,
  CreateBookingRequest,
  CsrfInfo,
  OperationStatus,
  Quote,
  QuoteRequest,
  SessionInfo,
} from "./types";

const API_BASE = "/api/v2";
const NETWORK_MESSAGE =
  "Не удалось связаться с сервисом. Проверьте подключение и повторите действие.";
const HTTP_MESSAGES: Record<number, string> = {
  401: "Сессия истекла. Войдите, чтобы продолжить.",
  403: "Для этого действия недостаточно прав.",
  404: "Объект не найден.",
  409: "Условия изменились. Обновите данные и проверьте запрос снова.",
  429: "Слишком много попыток. Повторите позже.",
  502: "Сервис временно недоступен.",
  503: "Сервис временно недоступен.",
  504: "Сервис временно недоступен.",
};

export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;
  readonly traceId: string | null;
  readonly retryAfterSeconds: number | null;
  readonly fieldErrors: ApiError["fieldErrors"] | null;
  readonly details: ApiError["details"] | null;

  constructor(options: {
    status: number;
    code: string;
    message: string;
    traceId?: string | null;
    retryAfterSeconds?: number | null;
    fieldErrors?: ApiError["fieldErrors"] | null;
    details?: ApiError["details"] | null;
  }) {
    super(options.message);
    this.name = "ApiClientError";
    this.status = options.status;
    this.code = options.code;
    this.traceId = options.traceId ?? null;
    this.retryAfterSeconds = options.retryAfterSeconds ?? null;
    this.fieldErrors = options.fieldErrors ?? null;
    this.details = options.details ?? null;
  }
}

type Fetch = typeof fetch;
type CachedCsrf = { info: CsrfInfo; expiresAt: number };
export type ApiClient = {
  csrf(signal?: AbortSignal): Promise<CsrfInfo>;
  me(signal?: AbortSignal): Promise<SessionInfo>;
  login(
    credentials: { username: string; password: string },
    signal?: AbortSignal,
  ): Promise<SessionInfo>;
  logout(signal?: AbortSignal): Promise<void>;
  get<T>(path: string, signal?: AbortSignal): Promise<T>;
  quote(
    selection: QuoteRequest,
    key: string,
    signal?: AbortSignal,
  ): Promise<Quote>;
  createBooking(
    body: CreateBookingRequest | null,
    key: string,
    signal?: AbortSignal,
  ): Promise<BookingCreationReceipt>;
  bookingResult(key: string, signal?: AbortSignal): Promise<OperationStatus>;
  fenceBooking(key: string, signal?: AbortSignal): Promise<OperationStatus>;
  ownBookings(
    query?: { page?: number; status?: "CONFIRMED" | "CANCELLED" },
    signal?: AbortSignal,
  ): Promise<BookingPage>;
  ownBooking(id: string, signal?: AbortSignal): Promise<Booking>;
  cancelBooking(id: string, signal?: AbortSignal): Promise<Booking>;
};

const UUID_V4 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const POSITIVE_ID = /^[1-9][0-9]*$/;

function assertKey(key: string): void {
  if (!UUID_V4.test(key))
    throw new TypeError("Для операции нужен UUIDv4 ключ идемпотентности.");
}

function assertBookingId(id: string): void {
  if (!POSITIVE_ID.test(id))
    throw new TypeError(
      "Идентификатор брони должен быть положительным числом.",
    );
}

function isAbort(error: unknown, signal?: AbortSignal): boolean {
  return (
    signal?.aborted === true ||
    (error instanceof Error && error.name === "AbortError")
  );
}

function abortError(signal?: AbortSignal): Error {
  if (signal?.reason instanceof Error) return signal.reason;
  if (typeof DOMException !== "undefined")
    return new DOMException("The operation was aborted", "AbortError");
  const error = new Error("The operation was aborted");
  error.name = "AbortError";
  return error;
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw abortError(signal);
}

function waitWithSignal<T>(
  promise: Promise<T>,
  signal?: AbortSignal,
): Promise<T> {
  if (!signal) return promise;
  if (signal.aborted) return Promise.reject(abortError(signal));
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => {
      signal.removeEventListener("abort", onAbort);
      reject(abortError(signal));
    };
    signal.addEventListener("abort", onAbort, { once: true });
    promise.then(
      (value) => {
        signal.removeEventListener("abort", onAbort);
        resolve(value);
      },
      (error: unknown) => {
        signal.removeEventListener("abort", onAbort);
        reject(error);
      },
    );
  });
}

function parseRetryAfter(value: string | null): number | null {
  if (value === null || !/^[1-9][0-9]*$/.test(value)) return null;
  const seconds = Number(value);
  return Number.isSafeInteger(seconds) ? seconds : null;
}

function safeApiError(value: unknown, status: number): value is ApiError {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Partial<ApiError>;
  return (
    candidate.status === status &&
    typeof candidate.code === "string" &&
    /^[A-Z][A-Z0-9_]{0,63}$/.test(candidate.code) &&
    typeof candidate.message === "string" &&
    candidate.message.length <= 500 &&
    (candidate.traceId === null || typeof candidate.traceId === "string") &&
    Array.isArray(candidate.fieldErrors) &&
    typeof candidate.details === "object" &&
    candidate.details !== null
  );
}

function fallbackMessage(status: number): string {
  if (HTTP_MESSAGES[status]) return HTTP_MESSAGES[status];
  if (status >= 500) return "Сервис временно недоступен.";
  return "Не удалось выполнить запрос. Проверьте данные и повторите действие.";
}

function networkError(): ApiClientError {
  return new ApiClientError({
    status: 0,
    code: "NETWORK_ERROR",
    message: NETWORK_MESSAGE,
  });
}

function httpError(response: Response): Promise<ApiClientError> {
  const retryAfterSeconds = parseRetryAfter(
    response.headers.get("Retry-After"),
  );
  const contentType = response.headers.get("Content-Type") ?? "";
  if (!contentType.toLowerCase().includes("application/json")) {
    return Promise.resolve(
      new ApiClientError({
        status: response.status,
        code: response.status >= 500 ? "SERVICE_UNAVAILABLE" : "HTTP_ERROR",
        message: fallbackMessage(response.status),
        traceId: response.headers.get("X-Trace-Id"),
        retryAfterSeconds,
      }),
    );
  }
  return response
    .json()
    .then((body: unknown) => {
      if (safeApiError(body, response.status)) {
        return new ApiClientError({
          status: response.status,
          code: body.code,
          message: body.message,
          traceId: body.traceId,
          retryAfterSeconds,
          fieldErrors: body.fieldErrors,
          details: body.details,
        });
      }
      return new ApiClientError({
        status: response.status,
        code: response.status >= 500 ? "SERVICE_UNAVAILABLE" : "HTTP_ERROR",
        message: fallbackMessage(response.status),
        traceId: response.headers.get("X-Trace-Id"),
        retryAfterSeconds,
      });
    })
    .catch(
      () =>
        new ApiClientError({
          status: response.status,
          code: response.status >= 500 ? "SERVICE_UNAVAILABLE" : "HTTP_ERROR",
          message: fallbackMessage(response.status),
          traceId: response.headers.get("X-Trace-Id"),
          retryAfterSeconds,
        }),
    );
}

function validateGetPath(path: string): string {
  if (
    !path.startsWith("/") ||
    path.startsWith("//") ||
    path.includes("\\") ||
    path.includes("#") ||
    /[\u0000-\u001f\u007f]/.test(path)
  ) {
    throw new TypeError("Путь запроса должен быть безопасным локальным URL.");
  }
  const queryAt = path.indexOf("?");
  const pathname = queryAt < 0 ? path : path.slice(0, queryAt);
  const query = queryAt < 0 ? "" : path.slice(queryAt + 1);
  if (!/^\/[A-Za-z0-9._~/-]*$/.test(pathname) || pathname.includes("//")) {
    throw new TypeError("Путь запроса должен быть безопасным локальным URL.");
  }
  const segments = pathname.split("/").slice(1);
  if (
    segments.some(
      (segment) => segment === "" || segment === "." || segment === "..",
    )
  ) {
    throw new TypeError("Путь запроса должен быть безопасным локальным URL.");
  }

  const hotel = /^\/hotels\/([a-z0-9]+(?:-[a-z0-9]+)*)$/.test(pathname);
  const hotelRooms = /^\/hotels\/([a-z0-9]+(?:-[a-z0-9]+)*)\/rooms$/.test(
    pathname,
  );
  const room = /^\/rooms\/[1-9][0-9]*$/.test(pathname);
  const booking = /^\/me\/bookings(?:\/[1-9][0-9]*)?$/.test(pathname);
  const allowed =
    pathname === "/cities" ||
    pathname === "/hotels" ||
    hotel ||
    hotelRooms ||
    room ||
    pathname === "/auth/csrf" ||
    pathname === "/auth/me" ||
    booking;
  if (!allowed)
    throw new TypeError(
      "Этот GET-путь недоступен через браузерный API-клиент.",
    );

  if (query === "") return `${API_BASE}${pathname}`;
  const queryKeys =
    pathname === "/hotels"
      ? new Set([
          "city",
          "checkInDate",
          "checkOutDate",
          "guests",
          "priceMin",
          "priceMax",
          "page",
          "size",
          "sort",
        ])
      : hotelRooms || room
        ? new Set([
            "checkInDate",
            "checkOutDate",
            "guests",
            "priceMin",
            "priceMax",
            "page",
            "size",
            "sort",
          ])
        : booking && pathname === "/me/bookings"
          ? new Set(["page", "size", "status", "sort"])
          : new Set<string>();
  const seen = new Set<string>();
  for (const pair of query.split("&")) {
    if (!pair) throw new TypeError("Пустой параметр запроса недопустим.");
    const equal = pair.indexOf("=");
    const rawKey = equal < 0 ? pair : pair.slice(0, equal);
    const rawValue = equal < 0 ? "" : pair.slice(equal + 1);
    if (
      !/^[A-Za-z][A-Za-z0-9]*$/.test(rawKey) ||
      !/^[A-Za-z0-9._~-]*$/.test(rawValue)
    ) {
      throw new TypeError("Параметр запроса имеет небезопасный формат.");
    }
    if (!queryKeys.has(rawKey) || seen.has(rawKey))
      throw new TypeError("Неизвестный или повторный параметр запроса.");
    seen.add(rawKey);
  }
  return `${API_BASE}${path}`;
}

export function createApiClient(fetchImpl: Fetch = fetch): ApiClient {
  let csrfValue: CachedCsrf | null = null;
  let csrfFlight: Promise<CsrfInfo> | null = null;
  let csrfGeneration = 0;
  let authQueue: Promise<void> = Promise.resolve();
  let authTransitionPending = false;

  function invalidateCsrf(): void {
    csrfGeneration += 1;
    csrfValue = null;
    csrfFlight = null;
  }

  async function requestJson<T>(
    path: string,
    init: RequestInit,
    signal?: AbortSignal,
  ): Promise<T> {
    throwIfAborted(signal);
    let response: Response;
    try {
      response = await fetchImpl(`${API_BASE}${path}`, {
        ...init,
        credentials: "same-origin",
        cache: "no-store",
        signal,
        headers: (() => {
          const headers = new Headers(init.headers);
          headers.set("Accept", "application/json");
          return headers;
        })(),
      });
    } catch (error) {
      if (isAbort(error, signal)) throw error;
      throw networkError();
    }
    if (!response.ok) {
      const error = await httpError(response);
      if (error.code === "CSRF_INVALID") invalidateCsrf();
      throw error;
    }
    if (response.status === 204) return undefined as T;
    const contentType = response.headers.get("Content-Type") ?? "";
    if (!contentType.toLowerCase().includes("application/json")) {
      throw new ApiClientError({
        status: response.status,
        code: "INVALID_RESPONSE",
        message: "Сервис вернул ответ в неожиданном формате.",
      });
    }
    try {
      return (await response.json()) as T;
    } catch (error) {
      if (isAbort(error, signal)) throw error;
      throw new ApiClientError({
        status: response.status,
        code: "INVALID_RESPONSE",
        message: "Сервис вернул некорректный ответ.",
      });
    }
  }

  async function fetchCsrf(): Promise<CsrfInfo> {
    const info = await requestJson<CsrfInfo>("/auth/csrf", { method: "GET" });
    const expiresAt = Date.parse(info.expiresAt);
    if (
      info.headerName !== "X-CSRF-TOKEN" ||
      typeof info.token !== "string" ||
      info.token.length < 32 ||
      !Number.isFinite(expiresAt)
    ) {
      throw new ApiClientError({
        status: 502,
        code: "INVALID_RESPONSE",
        message: "Сервис вернул некорректный ответ.",
      });
    }
    return info;
  }

  async function ensureCsrf(signal?: AbortSignal): Promise<CsrfInfo> {
    for (;;) {
      throwIfAborted(signal);
      const now = Date.now();
      if (csrfValue && now < csrfValue.expiresAt) return csrfValue.info;
      const generation = csrfGeneration;
      let flight = csrfFlight;
      if (!flight) {
        flight = fetchCsrf();
        csrfFlight = flight;
        flight
          .then((info) => {
            if (generation === csrfGeneration)
              csrfValue = { info, expiresAt: Date.parse(info.expiresAt) };
          })
          .finally(() => {
            if (csrfFlight === flight) csrfFlight = null;
          })
          .catch(() => undefined);
      }
      const info = await waitWithSignal(flight, signal);
      if (generation !== csrfGeneration) continue;
      if (Date.now() >= Date.parse(info.expiresAt)) {
        invalidateCsrf();
        throw new ApiClientError({
          status: 502,
          code: "INVALID_RESPONSE",
          message:
            "Не удалось обновить защиту формы. Проверьте время устройства и повторите действие.",
        });
      }
      return info;
    }
  }

  async function getCsrf(signal?: AbortSignal): Promise<CsrfInfo> {
    while (authTransitionPending) await waitWithSignal(authQueue, signal);
    return waitWithSignal(ensureCsrf(signal), signal);
  }

  function enqueueAuth<T>(
    work: () => Promise<T>,
    signal?: AbortSignal,
  ): Promise<T> {
    const run = authQueue.then(async () => {
      throwIfAborted(signal);
      authTransitionPending = true;
      try {
        return await work();
      } finally {
        authTransitionPending = false;
      }
    });
    authQueue = run.then(
      () => undefined,
      () => undefined,
    );
    return waitWithSignal(run, signal);
  }

  async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
    const safePath = validateGetPath(path);
    return requestJson<T>(
      safePath.slice(API_BASE.length),
      { method: "GET" },
      signal,
    );
  }

  return {
    csrf: getCsrf,
    me: (signal) => get<SessionInfo>("/auth/me", signal),
    get,
    async quote(selection, key, signal) {
      assertKey(key);
      return enqueueAuth(async () => {
        const csrf = await ensureCsrf(signal);
        return requestJson<Quote>(
          "/bookings/quote",
          {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              [csrf.headerName]: csrf.token,
              "Idempotency-Key": key,
            },
            body: JSON.stringify(selection),
          },
          signal,
        );
      }, signal);
    },
    async createBooking(body, key, signal) {
      assertKey(key);
      return enqueueAuth(async () => {
        const csrf = await ensureCsrf(signal);
        return requestJson<BookingCreationReceipt>(
          "/bookings",
          {
            method: "POST",
            headers: {
              [csrf.headerName]: csrf.token,
              "Idempotency-Key": key,
              ...(body ? { "Content-Type": "application/json" } : {}),
            },
            ...(body ? { body: JSON.stringify(body) } : {}),
          },
          signal,
        );
      }, signal);
    },
    async bookingResult(key, signal) {
      assertKey(key);
      return requestJson<OperationStatus>(
        "/me/booking-operations/result",
        {
          method: "GET",
          headers: { "Idempotency-Key": key },
        },
        signal,
      );
    },
    async fenceBooking(key, signal) {
      assertKey(key);
      return enqueueAuth(async () => {
        const csrf = await ensureCsrf(signal);
        return requestJson<OperationStatus>(
          "/me/booking-operations/fence",
          {
            method: "POST",
            headers: { [csrf.headerName]: csrf.token, "Idempotency-Key": key },
          },
          signal,
        );
      }, signal);
    },
    async ownBookings(query = {}, signal) {
      const params = new URLSearchParams();
      if (query.page !== undefined) {
        if (!Number.isSafeInteger(query.page) || query.page < 0)
          throw new TypeError(
            "Номер страницы должен быть целым неотрицательным числом.",
          );
        params.set("page", String(query.page));
      }
      if (query.status !== undefined) params.set("status", query.status);
      const queryString = params.toString();
      const suffix = queryString ? `?${queryString}` : "";
      return get<BookingPage>(`/me/bookings${suffix}`, signal);
    },
    async ownBooking(id, signal) {
      assertBookingId(id);
      return get<Booking>(`/me/bookings/${id}`, signal);
    },
    async cancelBooking(id, signal) {
      assertBookingId(id);
      return enqueueAuth(async () => {
        const csrf = await ensureCsrf(signal);
        return requestJson<Booking>(
          `/me/bookings/${id}/cancel`,
          {
            method: "POST",
            headers: { [csrf.headerName]: csrf.token },
          },
          signal,
        );
      }, signal);
    },
    login(credentials, signal) {
      return enqueueAuth(async () => {
        const csrf = await ensureCsrf(signal);
        // Credentials are only serialized into the JSON request body, never a URL.
        throwIfAborted(signal);
        try {
          await requestJson<SessionInfo>(
            "/auth/login",
            {
              method: "POST",
              headers: {
                "Content-Type": "application/json",
                [csrf.headerName]: csrf.token,
              },
              body: JSON.stringify(credentials),
            },
            signal,
          );
        } catch (error) {
          if (
            !(error instanceof ApiClientError) ||
            (error.code !== "INVALID_CREDENTIALS" &&
              error.code !== "RATE_LIMITED")
          )
            invalidateCsrf();
          throw error;
        }
        invalidateCsrf();
        await ensureCsrf(signal);
        return get<SessionInfo>("/auth/me", signal);
      }, signal);
    },
    logout(signal) {
      return enqueueAuth(async () => {
        const csrf = await ensureCsrf(signal);
        try {
          await requestJson<void>(
            "/auth/logout",
            {
              method: "POST",
              headers: { [csrf.headerName]: csrf.token },
            },
            signal,
          );
          invalidateCsrf();
        } catch (error) {
          if (
            !(error instanceof ApiClientError) ||
            error.code !== "RATE_LIMITED"
          )
            invalidateCsrf();
          throw error;
        }
      }, signal);
    },
  };
}
