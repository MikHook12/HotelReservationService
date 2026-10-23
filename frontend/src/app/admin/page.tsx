"use client";
import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { createAdminClient } from "@/lib/admin-client";
import type { AdminHotel, AdminPage } from "@/lib/admin-types";

export default function AdminPage() {
  const client = useRef(createAdminClient());
  const [page, setPage] = useState(0),
    [data, setData] = useState<AdminPage<AdminHotel> | null>(null),
    [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    setData(null);
    setError("");
    client.current
      .hotels(page)
      .then((value) => {
        if (active) setData(value);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [page]);
  return (
    <section className="panel stack">
      <div className="admin-actions">
        <div>
          <p className="eyebrow">Управление каталогом</p>
          <h1>Отели</h1>
        </div>
        <Link className="button" href="/admin/hotels/new">
          Добавить отель
        </Link>
      </div>
      <p className="muted">
        Скрытые отели не появляются в каталоге. Существующие брони сохраняются.
      </p>
      {error && (
        <p role="alert" className="feedback error">
          {error}
        </p>
      )}
      {!data && !error && <p role="status">Загружаем отели…</p>}
      {data && (
        <>
          <p>Всего: {data.totalElements}</p>
          <p className="muted admin-table-hint">Таблицу можно прокрутить по горизонтали.</p>
          <div className="admin-table" role="region" aria-label="Список отелей" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th>Название</th>
                  <th>Город</th>
                  <th>Публикация</th>
                  <th>Действия</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((hotel) => (
                  <tr key={hotel.id}>
                    <td>{hotel.name}</td>
                    <td>{hotel.city}</td>
                    <td>{hotel.published ? "Опубликован" : "Скрыт"}</td>
                    <td>
                      <Link href={`/admin/hotels/${hotel.id}`}>
                        Изменить и настроить комнаты
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {data.items.length === 0 && (
            <p>Пока нет отелей. Добавьте первый объект.</p>
          )}
          <div className="admin-actions">
            <button
              className="button secondary"
              disabled={page === 0}
              onClick={() => setPage(page - 1)}
            >
              Назад
            </button>
            <span>
              Страница {page + 1} из {Math.max(1, data.totalPages)}
            </span>
            <button
              className="button secondary"
              disabled={page + 1 >= data.totalPages}
              onClick={() => setPage(page + 1)}
            >
              Далее
            </button>
          </div>
        </>
      )}
    </section>
  );
}
