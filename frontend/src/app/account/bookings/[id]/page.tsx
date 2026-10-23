import { Suspense } from "react";
import { Account } from "../account";

function DetailFallback() {
  return (
    <section
      className="panel stack"
      role="status"
      aria-live="polite"
      aria-busy="true"
    >
      <p className="eyebrow">Личный кабинет</p>
      <h1>Загружаем бронь…</h1>
      <p className="muted">
        Сведения доступны только после проверки вашей сессии.
      </p>
    </section>
  );
}

export default function BookingPage() {
  return (
    <div className="wrap">
      <Suspense fallback={<DetailFallback />}>
        <Account />
      </Suspense>
    </div>
  );
}
