"use client";
import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ApiClientError } from "@/lib/api-client";
import { createAdminClient } from "@/lib/admin-client";
import type {
  AdminHotelDraft,
  AdminRoom,
  AdminRoomDraft,
  AdminPage,
} from "@/lib/admin-types";
import { PhotoFields } from "./photo-fields";

const emptyHotel: AdminHotelDraft = {
  version: null,
  slug: "",
  name: "",
  city: "",
  cityCode: "",
  timeZone: "Europe/Moscow",
  address: "",
  description: "",
  distanceFromCityCenter: 0,
  published: false,
  photos: [],
};
const emptyRoom: AdminRoomDraft = {
  version: null,
  name: "",
  number: 1,
  capacity: 2,
  nightlyPrice: "1000.00",
  description: "",
  published: false,
  photos: [],
};
function saveError(e: unknown) {
  return e instanceof Error ? e.message : "Не удалось сохранить данные.";
}
function mustReload(e: unknown) {
  return (
    e instanceof ApiClientError &&
    (e.status === 409 || e.status === 0 || e.status >= 500)
  );
}

export function HotelEditor({ hotelId }: { hotelId: string | null }) {
  const activeSave = useRef<AbortController | null>(null);
  useEffect(() => () => activeSave.current?.abort(), []);
  const api = useRef(createAdminClient()),
    lock = useRef(false),
    router = useRouter();
  const [draft, setDraft] = useState<AdminHotelDraft>(emptyHotel),
    [loaded, setLoaded] = useState(!hotelId),
    [originalPublished, setOriginalPublished] = useState(false);
  const [busy, setBusy] = useState(false),
    [uploading, setUploading] = useState(false),
    [blocked, setBlocked] = useState(false),
    [error, setError] = useState(""),
    [success, setSuccess] = useState("");
  useEffect(() => {
    if (!hotelId) return;
    let active = true;
    setLoaded(false);
    api.current
      .hotel(hotelId)
      .then((value) => {
        if (active) {
          const { id: _, ...fields } = value;
          setDraft(fields);
          setOriginalPublished(value.published);
          setLoaded(true);
        }
      })
      .catch((e) => {
        if (active) setError(saveError(e));
      });
    return () => {
      active = false;
    };
  }, [hotelId]);
  function change<K extends keyof AdminHotelDraft>(
    key: K,
    value: AdminHotelDraft[K],
  ) {
    setDraft((current) => ({ ...current, [key]: value }));
    setSuccess("");
  }
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (lock.current || uploading || blocked) return;
    if (
      originalPublished &&
      !draft.published &&
      !window.confirm(
        "Скрыть отель и все его комнаты из публичного каталога? Существующие брони сохранятся.",
      )
    )
      return;
    const controller = new AbortController();
    activeSave.current = controller;
    lock.current = true;
    setBusy(true);
    setError("");
    setSuccess("");
    try {
      const saved = await api.current.saveHotel(
        hotelId,
        draft,
        controller.signal,
      );
      if (controller.signal.aborted) return;
      if (hotelId === null) {
        router.replace(`/admin/hotels/${saved.id}`);
      } else {
        const { id: _, ...fields } = saved;
        setDraft(fields);
        setOriginalPublished(saved.published);
        setSuccess("Изменения отеля сохранены.");
      }
    } catch (e) {
      if (!controller.signal.aborted) {
        setError(saveError(e));
        if (mustReload(e)) setBlocked(true);
      }
    } finally {
      lock.current = false;
      if (!controller.signal.aborted) setBusy(false);
    }
  }
  return (
    <div className="stack">
      <Link href="/admin">← Все отели</Link>
      <section className="panel stack">
        <h1>{hotelId ? "Редактирование отеля" : "Новый отель"}</h1>
        {error && (
          <p className="feedback error" role="alert">
            {error}
          </p>
        )}
        {success && (
          <p className="feedback" role="status">
            {success}
          </p>
        )}
        {blocked && (
          <p>
            <Link href="/admin">Проверить список отелей</Link>. Перед повторным
            сохранением перезагрузите страницу. Несохранённый текст скопируйте
            вручную.
          </p>
        )}
        {!loaded && !error && <p role="status">Загружаем отель…</p>}
        {loaded && (
          <form
            onSubmit={submit}
            className="admin-form stack"
            aria-busy={busy || uploading}
          >
            <fieldset disabled={busy || uploading} className="stack">
              <div className="admin-grid">
                <label className="field">
                  Название
                  <input
                    required
                    maxLength={200}
                    value={draft.name}
                    onChange={(e) => change("name", e.target.value)}
                  />
                </label>
                <label className="field">
                  Адрес страницы (slug)
                  <input
                    required
                    pattern="(?=.*[a-z])[a-z0-9]+(-[a-z0-9]+)*"
                    maxLength={100}
                    readOnly={hotelId !== null}
                    value={draft.slug}
                    onChange={(e) => change("slug", e.target.value)}
                  />
                  <small>
                    Например: lesnoy-bereg. После создания адрес страницы не меняется.
                  </small>
                </label>
                <label className="field">
                  Город
                  <input
                    required
                    maxLength={100}
                    value={draft.city}
                    onChange={(e) => change("city", e.target.value)}
                  />
                </label>
                <label className="field">
                  Код города
                  <input
                    required
                    maxLength={64}
                    pattern="[a-z0-9]+(-[a-z0-9]+)*"
                    value={draft.cityCode}
                    onChange={(e) => change("cityCode", e.target.value)}
                  />
                  <small>
                    Одинаковый для всех отелей города, например moscow.
                  </small>
                </label>
                <label className="field">
                  Часовой пояс
                  <input
                    required
                    maxLength={64}
                    value={draft.timeZone}
                    onChange={(e) => change("timeZone", e.target.value)}
                  />
                  <small>
                    Например Europe/Moscow. Влияет на сроки брони и отмены.
                  </small>
                </label>
                <label className="field">
                  До центра, км
                  <input
                    type="number"
                    required
                    min="0"
                    step="0.1"
                    value={draft.distanceFromCityCenter}
                    onChange={(e) =>
                      change("distanceFromCityCenter", Number(e.target.value))
                    }
                  />
                </label>
              </div>
              <label className="field">
                Адрес
                <input
                  required
                  maxLength={255}
                  value={draft.address}
                  onChange={(e) => change("address", e.target.value)}
                />
              </label>
              <label className="field">
                Описание
                <textarea
                  required
                  maxLength={10000}
                  rows={5}
                  value={draft.description}
                  onChange={(e) => change("description", e.target.value)}
                />
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={draft.published}
                  onChange={(e) => change("published", e.target.checked)}
                />{" "}
                Показывать отель в каталоге
              </label>
              <PhotoFields
                photos={draft.photos}
                onChange={(photos) => change("photos", photos)}
                onBusy={setUploading}
              />
              <button className="button" type="submit" disabled={blocked}>
                {busy
                  ? "Сохраняем…"
                  : uploading
                    ? "Загружаем фото…"
                    : "Сохранить отель"}
              </button>
            </fieldset>
          </form>
        )}
      </section>
      {hotelId && loaded && <Rooms hotelId={hotelId} />}
    </div>
  );
}

function Rooms({ hotelId }: { hotelId: string }) {
  const api = useRef(createAdminClient());
  const [data, setData] = useState<AdminPage<AdminRoom> | null>(null),
    [page, setPage] = useState(0),
    [revision, setRevision] = useState(0),
    [error, setError] = useState("");
  const [editing, setEditing] = useState<AdminRoom | "new" | null>(null);
  useEffect(() => {
    let active = true;
    setData(null);
    setError("");
    api.current
      .rooms(hotelId, page)
      .then((value) => {
        if (active) setData(value);
      })
      .catch((e) => {
        if (active) setError(saveError(e));
      });
    return () => {
      active = false;
    };
  }, [hotelId, page, revision]);
  return (
    <section className="panel stack">
      <div className="admin-actions">
        <h2>Комнаты</h2>
        <button
          className="button"
          disabled={editing !== null}
          onClick={() => setEditing("new")}
        >
          Добавить комнату
        </button>
      </div>
      {error && (
        <p className="feedback error" role="alert">
          {error}
        </p>
      )}
      {!data && !error && <p role="status">Загружаем комнаты…</p>}
      {data && (
        <>
          <div className="admin-table">
            <table>
              <thead>
                <tr>
                  <th>Номер / название</th>
                  <th>Гостей</th>
                  <th>Цена за ночь</th>
                  <th>Публикация</th>
                  <th>Действия</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((room) => (
                  <tr key={room.id}>
                    <td>
                      № {room.number} · {room.name}
                    </td>
                    <td>{room.capacity}</td>
                    <td>{room.nightlyPrice} ₽</td>
                    <td>{room.published ? "Опубликована" : "Скрыта"}</td>
                    <td>
                      <button
                        className="button secondary"
                        disabled={editing !== null}
                        onClick={() => setEditing(room)}
                      >
                        Изменить
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!data.items.length && <p>Добавьте первую комнату и задайте цену.</p>}
          <div className="admin-actions">
            <button
              className="button secondary"
              disabled={page === 0 || editing !== null}
              onClick={() => setPage(page - 1)}
            >
              Назад
            </button>
            <span>
              Страница {page + 1} из {Math.max(1, data.totalPages)}
            </span>
            <button
              className="button secondary"
              disabled={page + 1 >= data.totalPages || editing !== null}
              onClick={() => setPage(page + 1)}
            >
              Далее
            </button>
          </div>
        </>
      )}
      {editing !== null && (
        <RoomEditor
          key={editing === "new" ? "new" : editing.id}
          hotelId={hotelId}
          room={editing === "new" ? null : editing}
          onDone={() => {
            setEditing(null);
            setRevision((value) => value + 1);
          }}
        />
      )}
    </section>
  );
}

function RoomEditor({
  hotelId,
  room,
  onDone,
}: {
  hotelId: string;
  room: AdminRoom | null;
  onDone: () => void;
}) {
  const activeSave = useRef<AbortController | null>(null);
  useEffect(() => () => activeSave.current?.abort(), []);
  const api = useRef(createAdminClient()),
    lock = useRef(false);
  const [draft, setDraft] = useState<AdminRoomDraft>(() => {
    if (!room) return emptyRoom;
    const { id: _, hotelId: __, ...fields } = room;
    return fields;
  });
  const [busy, setBusy] = useState(false),
    [uploading, setUploading] = useState(false),
    [blocked, setBlocked] = useState(false),
    [error, setError] = useState("");
  function change<K extends keyof AdminRoomDraft>(
    key: K,
    value: AdminRoomDraft[K],
  ) {
    setDraft((current) => ({ ...current, [key]: value }));
  }
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (lock.current || uploading || blocked) return;
    if (
      room?.published &&
      !draft.published &&
      !window.confirm(
        "Скрыть комнату из каталога? Существующие брони сохранятся.",
      )
    )
      return;
    const controller = new AbortController();
    activeSave.current = controller;
    lock.current = true;
    setBusy(true);
    setError("");
    const price = draft.nightlyPrice.replace(",", ".");
    const [whole, decimals = ""] = price.split(".");
    const normalized = /^\d{1,10}(?:\.\d{1,2})?$/.test(price)
      ? `${BigInt(whole)}.${decimals.padEnd(2, "0")}`
      : price;
    try {
      await api.current.saveRoom(
        hotelId,
        room?.id ?? null,
        { ...draft, nightlyPrice: normalized },
        controller.signal,
      );
      if (!controller.signal.aborted) onDone();
    } catch (e) {
      if (!controller.signal.aborted) {
        setError(saveError(e));
        if (mustReload(e)) setBlocked(true);
      }
    } finally {
      lock.current = false;
      if (!controller.signal.aborted) setBusy(false);
    }
  }
  return (
    <form
      className="admin-form stack"
      onSubmit={submit}
      aria-busy={busy || uploading}
    >
      <h3>{room ? `Комната № ${room.number}` : "Новая комната"}</h3>
      {error && (
        <p role="alert" className="feedback error">
          {error}
        </p>
      )}
      {blocked && (
        <p>
          Перед повторной отправкой проверьте список комнат. Для перезагрузки
          списка нажмите «Закрыть форму»; введённые данные будут потеряны.
        </p>
      )}
      <fieldset className="stack" disabled={busy || uploading}>
        <div className="admin-grid">
          <label className="field">
            Название
            <input
              required
              maxLength={200}
              value={draft.name}
              onChange={(e) => change("name", e.target.value)}
            />
          </label>
          <label className="field">
            Номер
            <input
              required
              type="number"
              min="1"
              step="1"
              value={draft.number}
              onChange={(e) => change("number", Number(e.target.value))}
            />
          </label>
          <label className="field">
            Вместимость
            <input
              required
              type="number"
              min="1"
              max="100"
              step="1"
              value={draft.capacity}
              onChange={(e) => change("capacity", Number(e.target.value))}
            />
          </label>
          <label className="field">
            Цена за ночь, ₽
            <input
              required
              inputMode="decimal"
              pattern="[0-9]+([.,][0-9]{1,2})?"
              value={draft.nightlyPrice}
              onChange={(e) =>
                change("nightlyPrice", e.target.value.replace(",", "."))
              }
            />
            <small>Например 3500 или 3500,50.</small>
          </label>
        </div>
        <label className="field">
          Описание
          <textarea
            required
            maxLength={5000}
            rows={4}
            value={draft.description}
            onChange={(e) => change("description", e.target.value)}
          />
        </label>
        <label>
          <input
            type="checkbox"
            checked={draft.published}
            onChange={(e) => change("published", e.target.checked)}
          />{" "}
          Показывать комнату в каталоге
        </label>
        <p className="muted">
          Комната видна посетителям, только если опубликован и отель.
        </p>
        <PhotoFields
          photos={draft.photos}
          onChange={(photos) => change("photos", photos)}
          onBusy={setUploading}
        />
        <div className="admin-actions">
          <button className="button" disabled={blocked} type="submit">
            {busy
              ? "Сохраняем…"
              : uploading
                ? "Загружаем фото…"
                : "Сохранить комнату"}
          </button>
          <button
            className="button secondary"
            type="button"
            onClick={() => {
              if (window.confirm("Закрыть форму без сохранения?")) onDone();
            }}
          >
            Закрыть форму
          </button>
        </div>
      </fieldset>
    </form>
  );
}
