"use client";
export default function ErrorPage({ reset }: { reset: () => void }) {
  return (
    <div className="wrap panel stack" role="alert">
      <h1>Не удалось открыть страницу</h1>
      <p>Попробуйте ещё раз через некоторое время.</p>
      <button className="button" onClick={reset}>
        Повторить
      </button>
    </div>
  );
}
