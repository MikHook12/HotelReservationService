import { ApiClientError, createApiClient } from "./api-client";
import type {
  AdminHotel,
  AdminHotelDraft,
  AdminRoom,
  AdminRoomDraft,
  AdminPage,
} from "./admin-types";

function id(value: string): string {
  if (!/^[1-9][0-9]*$/.test(value))
    throw new TypeError("Некорректный идентификатор.");
  return value;
}
function pagination(page: number, size: number): string {
  if (
    !Number.isSafeInteger(page) ||
    page < 0 ||
    !Number.isSafeInteger(size) ||
    size < 1 ||
    size > 100
  )
    throw new TypeError("Некорректная страница.");
  return `?page=${page}&size=${size}`;
}
export function createAdminClient(fetchImpl: typeof fetch = fetch) {
  const auth = createApiClient(fetchImpl);
  async function request<T>(
    path: string,
    body?: object | FormData,
    signal?: AbortSignal,
  ): Promise<T> {
    const headers = new Headers({ Accept: "application/json" });
    if (body !== undefined) {
      const csrf = await auth.csrf(signal);
      headers.set(csrf.headerName, csrf.token);
      if (!(body instanceof FormData))
        headers.set("Content-Type", "application/json");
    }
    let response: Response;
    try {
      response = await fetchImpl(`/api/v2/admin${path}`, {
        signal,
        method: body === undefined ? "GET" : "POST",
        headers,
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
        ...(body === undefined
          ? {}
          : { body: body instanceof FormData ? body : JSON.stringify(body) }),
      });
    } catch (error) {
      if (signal?.aborted) throw error;
      throw new ApiClientError({
        status: 0,
        code: "NETWORK_ERROR",
        message:
          body === undefined
            ? "Не удалось загрузить данные. Проверьте подключение."
            : "Ответ не получен. Изменения могли сохраниться. Сначала обновите список и проверьте результат; повторная отправка может создать дубль.",
      });
    }
    if (!response.ok) {
      let code = "HTTP_ERROR";
      try {
        const value: unknown = await response.json();
        if (
          typeof value === "object" &&
          value !== null &&
          "code" in value &&
          typeof value.code === "string" &&
          /^[A-Z_0-9]{1,64}$/.test(value.code)
        )
          code = value.code;
      } catch {
        /* Never show HTML or internal server messages. */
      }
      throw new ApiClientError({
        status: response.status,
        code,
        message:
          response.status === 409
            ? "Данные изменились. Перезагрузите страницу и сравните изменения перед сохранением. Введённые данные оставлены в форме."
            : response.status === 401
              ? "Сессия истекла. Войдите снова."
              : response.status === 403
                ? "Доступ разрешён только администратору."
                : response.status === 404
                  ? "Объект не найден."
                  : response.status === 413
                    ? "Фотография слишком большая. Максимум 5 МБ."
                    : response.status >= 500
                      ? "Сервис недоступен. Изменения могли сохраниться: проверьте список перед повторной отправкой."
                      : "Запрос отклонён. Проверьте поля, уникальность адреса отеля и номера комнаты, формат фотографии.",
      });
    }
    try {
      if (!response.headers.get("content-type")?.includes("application/json"))
        throw new Error("Invalid response type");
      const value: unknown = await response.json();
      if (
        body !== undefined &&
        !(body instanceof FormData) &&
        (typeof value !== "object" ||
          value === null ||
          !("id" in value) ||
          typeof value.id !== "string" ||
          !/^[1-9][0-9]*$/.test(value.id) ||
          !("version" in value) ||
          typeof value.version !== "number" ||
          !Number.isSafeInteger(value.version) ||
          value.version < 0)
      )
        throw new Error("Invalid saved resource");
      return value as T;
    } catch (error) {
      if (signal?.aborted) throw error;
      throw new ApiClientError({
        status: 502,
        code: "INVALID_RESPONSE",
        message:
          "Ответ сервера не подтверждён. Изменения могли сохраниться; проверьте список перед повторной отправкой.",
      });
    }
  }
  return {
    hotels: (page = 0, size = 20, signal?: AbortSignal) =>
      request<AdminPage<AdminHotel>>(
        `/hotels${pagination(page, size)}`,
        undefined,
        signal,
      ),
    hotel: (hotelId: string, signal?: AbortSignal) =>
      request<AdminHotel>(`/hotels/${id(hotelId)}`, undefined, signal),
    saveHotel: (
      hotelId: string | null,
      draft: AdminHotelDraft,
      signal?: AbortSignal,
    ) =>
      request<AdminHotel>(
        hotelId === null ? "/hotels" : `/hotels/${id(hotelId)}`,
        draft,
        signal,
      ),
    rooms: (hotelId: string, page = 0, size = 20, signal?: AbortSignal) =>
      request<AdminPage<AdminRoom>>(
        `/hotels/${id(hotelId)}/rooms${pagination(page, size)}`,
        undefined,
        signal,
      ),
    saveRoom: (
      hotelId: string,
      roomId: string | null,
      draft: AdminRoomDraft,
      signal?: AbortSignal,
    ) =>
      request<AdminRoom>(
        roomId === null
          ? `/hotels/${id(hotelId)}/rooms`
          : `/rooms/${id(roomId)}`,
        draft,
        signal,
      ),
    upload: (file: File, signal?: AbortSignal) => {
      if (
        !["image/jpeg", "image/png"].includes(file.type) ||
        file.size > 5 * 1024 * 1024 ||
        file.size === 0
      )
        throw new TypeError("Выберите JPEG или PNG размером до 5 МБ.");
      const data = new FormData();
      data.set("file", file);
      return request<{ url: string }>("/media", data, signal);
    },
  };
}
