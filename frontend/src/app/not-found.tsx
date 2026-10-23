import Link from "next/link";
export default function NotFound() {
  return (
    <div className="wrap panel stack">
      <p className="eyebrow">404</p>
      <h1>Такого места здесь нет</h1>
      <p>Проверьте ссылку или вернитесь к коллекции.</p>
      <Link className="button" href="/demo/hotels">
        Открыть каталог
      </Link>
    </div>
  );
}
