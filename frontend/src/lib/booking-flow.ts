import { ApiClientError, type createApiClient } from "./api-client";
import type {
  QuoteRequest,
  Quote,
  BookingCreationReceipt,
  OperationStatus,
} from "./types";

type Api = Pick<
  ReturnType<typeof createApiClient>,
  "quote" | "createBooking" | "bookingResult" | "fenceBooking"
>;
type Storage = Pick<globalThis.Storage, "getItem" | "setItem" | "removeItem">;
export const PENDING_BOOKING = "hotel.booking.pending.v1";
const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export type ReviewState = {
  phase:
    | "loading"
    | "quoting"
    | "ready"
    | "submitting"
    | "unknown"
    | "processing"
    | "failed"
    | "error"
    | "succeeded";
  quote?: Quote;
  message?: string;
  receipt?: BookingCreationReceipt;
};
/** The pending key is saved before dispatch; no unknown outcome permits a new CREATE. */
export class BookingFlow {
  state: ReviewState = { phase: "loading" };
  private pending: string | null = null;
  private dead = false;
  private recoveryBlocked = false;
  get canPrepare() {
    return (
      !this.dead &&
      !this.pending &&
      !this.recoveryBlocked &&
      !["submitting", "quoting"].includes(this.state.phase)
    );
  }
  constructor(
    private api: Api,
    private selection: QuoteRequest,
    private ownerId: string,
    private storage: Storage | null,
    private changed: (s: ReviewState) => void,
    private key: () => string = () => crypto.randomUUID(),
    private signal?: AbortSignal,
  ) {}
  private set(state: ReviewState) {
    if (!this.dead) {
      this.state = state;
      this.changed(state);
    }
  }
  dispose() {
    this.dead = true;
  }
  private message(e: unknown) {
    return e instanceof ApiClientError
      ? e.message
      : "Не удалось связаться с сервисом. Повторите проверку.";
  }
  private clear() {
    const key = this.pending;
    this.pending = null;
    try {
      const raw = this.storage?.getItem(PENDING_BOOKING);
      if (raw) {
        const saved = JSON.parse(raw);
        if (saved.key === key && saved.ownerId === this.ownerId)
          this.storage?.removeItem(PENDING_BOOKING);
      }
    } catch {
      /* A stale key remains safe: next page will look up the same operation. */
    }
  }
  async start() {
    if (this.dead || this.signal?.aborted) return;
    try {
      const raw = this.storage?.getItem(PENDING_BOOKING);
      if (raw) {
        const saved = JSON.parse(raw);
        if (
          saved.version !== 1 ||
          typeof saved.ownerId !== "string" ||
          typeof saved.key !== "string" ||
          !UUID.test(saved.key)
        )
          throw new Error("invalid recovery context");
        if (saved.ownerId === this.ownerId) {
          this.pending = saved.key;
          await this.check();
          return;
        }
        // Owner-bound operations must never be resumed under another identity.
        this.storage?.removeItem(PENDING_BOOKING);
      }
    } catch {
      this.recoveryBlocked = true;
      this.set({
        phase: "error",
        message:
          "Не удалось прочитать контекст отправленной операции. Проверьте свои брони в кабинете перед новым подтверждением.",
      });
      return;
    }
    await this.prepare();
  }
  async prepare() {
    if (!this.canPrepare) return;
    this.set({ phase: "quoting" });
    try {
      const quote = await this.api.quote(
        this.selection,
        this.key(),
        this.signal,
      );
      this.set({ phase: "ready", quote });
    } catch (e) {
      this.set({ phase: "error", message: this.message(e) });
    }
  }
  async confirm(consent: boolean) {
    if (
      this.dead ||
      !consent ||
      this.pending ||
      this.state.phase !== "ready" ||
      !this.state.quote
    )
      return;
    const quote = this.state.quote,
      key = this.key();
    try {
      if (!this.storage) throw new Error("storage unavailable");
      const raw = JSON.stringify({ version: 1, ownerId: this.ownerId, key });
      this.storage.setItem(PENDING_BOOKING, raw);
      if (this.storage.getItem(PENDING_BOOKING) !== raw)
        throw new Error("storage unavailable");
    } catch {
      this.set({
        phase: "error",
        message:
          "Не удалось сохранить ключ восстановления. Бронь не отправлена. Разрешите хранилище вкладки и обновите расчёт.",
      });
      return;
    }
    this.pending = key;
    this.set({ phase: "submitting", quote });
    try {
      const receipt = await this.api.createBooking(
        { ...this.selection, quoteToken: quote.quoteToken },
        key,
        this.signal,
      );
      if (this.dead) return;
      if (
        !receipt ||
        !/^[1-9][0-9]*$/.test(receipt.bookingId) ||
        receipt.statusAtCreation !== "CONFIRMED"
      )
        throw new Error("invalid receipt");
      this.clear();
      this.set({ phase: "succeeded", receipt });
    } catch (e) {
      // Even 404/5xx or an aborted client does not prove that a delayed POST cannot commit.
      this.set({
        phase: "unknown",
        message:
          "Ответ на подтверждение не получен. Проверяем исходную операцию; новую бронь не отправляем.",
      });
      if (!this.dead && !this.signal?.aborted) await this.check();
    }
  }
  private resolved(result: OperationStatus) {
    if (
      !result ||
      result.key !== this.pending ||
      !["SUCCEEDED", "FAILED", "PROCESSING"].includes(result.state)
    )
      throw new Error("invalid operation response");
    if (
      result.state === "SUCCEEDED" &&
      (!result.receipt || !/^[1-9][0-9]*$/.test(result.receipt.bookingId))
    )
      throw new Error("invalid receipt");
    if (result.state === "SUCCEEDED") {
      this.clear();
      this.set({ phase: "succeeded", receipt: result.receipt });
    } else if (result.state === "FAILED") {
      this.clear();
      this.set({
        phase: "failed",
        message:
          result.error?.message ||
          "Сервер подтвердил: эта операция завершена без создания брони. Можно получить новый расчёт.",
      });
    } else
      this.set({
        phase: "processing",
        message:
          "Сервер ещё обрабатывает подтверждение. Не отправляйте другую бронь на те же даты.",
      });
  }
  async check() {
    if (this.dead || !this.pending || this.state.phase === "submitting") return;
    const key = this.pending;
    try {
      const result = await this.api.bookingResult(key, this.signal);
      if (!this.dead && key === this.pending) this.resolved(result);
    } catch (e) {
      if (key !== this.pending) return;
      this.set({
        phase: "unknown",
        message:
          e instanceof ApiClientError && e.status === 404
            ? "Операция пока не найдена. Это ещё не доказывает, что исходный запрос не придёт позже. Проверьте снова или закройте непринятый запрос."
            : this.message(e),
      });
    }
  }
  async fence() {
    if (
      this.dead ||
      !this.pending ||
      !["unknown", "processing"].includes(this.state.phase)
    )
      return;
    const key = this.pending;
    this.set({
      phase: "processing",
      message:
        "Проверяем и закрываем только ещё не принятый запрос. Созданная бронь не отменяется.",
    });
    try {
      const result = await this.api.fenceBooking(key, this.signal);
      if (!this.dead && key === this.pending) this.resolved(result);
    } catch (e) {
      if (key === this.pending)
        this.set({ phase: "unknown", message: this.message(e) });
    }
  }
}
