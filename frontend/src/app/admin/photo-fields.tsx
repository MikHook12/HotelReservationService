"use client";
import { useEffect, useRef, useState } from "react";
import { createAdminClient } from "@/lib/admin-client";
import type { AdminPhoto } from "@/lib/admin-types";

export function PhotoFields({
  photos,
  onChange,
  onBusy,
}: {
  photos: AdminPhoto[];
  onChange: (value: AdminPhoto[]) => void;
  onBusy: (value: boolean) => void;
}) {
  const client = useRef(createAdminClient()),
    locked = useRef(false),
    active = useRef<AbortController | null>(null);
  useEffect(() => () => active.current?.abort(), []);
  const [error, setError] = useState("");
  async function upload(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file || locked.current || photos.length >= 10) return;
    const controller = new AbortController();
    active.current = controller;
    locked.current = true;
    onBusy(true);
    setError("");
    try {
      const result = await client.current.upload(file, controller.signal);
      if (controller.signal.aborted) return;
      if (
        !/^\/media\/[A-Za-z0-9._/-]+$/.test(result.url) ||
        result.url.includes("..")
      )
        throw new Error("Сервис вернул некорректный адрес фотографии.");
      onChange([
        ...photos,
        { url: result.url, alt: "", credit: "", license: "" },
      ]);
    } catch (e) {
      if (!controller.signal.aborted)
        setError(
          e instanceof Error ? e.message : "Не удалось загрузить фотографию.",
        );
    } finally {
      locked.current = false;
      if (!controller.signal.aborted) onBusy(false);
    }
  }
  function change(index: number, name: keyof AdminPhoto, value: string) {
    onChange(
      photos.map((photo, i) =>
        i === index ? { ...photo, [name]: value } : photo,
      ),
    );
  }
  return (
    <section className="stack">
      <h3>Фотографии</h3>
      <p className="muted">
        До 10 фотографий JPEG или PNG, каждая до 5 МБ. Загрузка добавляет фото в
        черновик. Нажмите «Сохранить», чтобы применить изменения.
      </p>
      {photos.map((photo, index) => (
        <div className="admin-photo stack" key={`${photo.url}-${index}`}>
          {/^\/media\/[A-Za-z0-9._/-]+$/.test(photo.url) &&
          !photo.url.includes("..") ? (
            <img
              src={photo.url}
              alt={photo.alt || "Предпросмотр загруженной фотографии"}
              width={240}
              height={160}
            />
          ) : (
            <p className="muted">Внешняя фотография: {photo.url}</p>
          )}
          <div className="admin-grid">
            <label className="field">
              Описание для читателей и скринридеров
              <input
                required
                maxLength={200}
                value={photo.alt}
                onChange={(e) => change(index, "alt", e.target.value)}
              />
            </label>
            <label className="field">
              Автор / источник
              <input
                required
                maxLength={300}
                value={photo.credit}
                onChange={(e) => change(index, "credit", e.target.value)}
              />
            </label>
            <label className="field">
              Лицензия / разрешение
              <input
                required
                maxLength={100}
                value={photo.license}
                onChange={(e) => change(index, "license", e.target.value)}
              />
            </label>
          </div>
          <button
            type="button"
            className="button secondary"
            onClick={() => onChange(photos.filter((_, i) => i !== index))}
          >
            Убрать из черновика
          </button>
        </div>
      ))}
      <label className="field">
        Добавить фотографию
        <input
          type="file"
          accept="image/jpeg,image/png"
          disabled={photos.length >= 10}
          onChange={upload}
        />
      </label>
      {error && (
        <p role="alert" className="feedback error">
          {error}
        </p>
      )}
    </section>
  );
}
