import Link from "next/link";
import type { Metadata } from "next";
export const metadata: Metadata = {
  title: "Регистрация",
  robots: { index: false, follow: false },
};
export default function Register() {
  return (
    <div className="wrap auth-shell">
      <section className="panel auth-card stack">
        <p className="eyebrow">Доступ к демо</p>
        <h1>Регистрация пока закрыта</h1>
        <p>
          Самостоятельная регистрация станет доступна после подключения
          подтверждения email и восстановления доступа.
        </p>
        <p>Если у вас уже есть учётная запись, используйте вход.</p>
        <Link className="button" href="/login">
          Войти
        </Link>
        <Link href="/demo/hotels">Продолжить просмотр каталога →</Link>
      </section>
    </div>
  );
}
