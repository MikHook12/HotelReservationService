import { Suspense } from "react";
import { Account } from "./account";

function AccountFallback() {
  return (
    <section
      className="panel stack"
      role="status"
      aria-live="polite"
      aria-busy="true"
    >
      <p className="eyebrow">Личный кабинет</p>
      <h1>Загружаем ваши брони…</h1>
      <p className="muted">Проверяем вход и запрашиваем личный список.</p>
    </section>
  );
}

export default function AccountPage() {
  return (
    <div className="wrap">
      <Suspense fallback={<AccountFallback />}>
        <Account />
      </Suspense>
    </div>
  );
}
