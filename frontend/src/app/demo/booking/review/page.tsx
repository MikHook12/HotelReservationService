import type { Metadata } from "next";
import Link from "next/link";
import { selectionFromQuery } from "@/lib/booking-selection";
import { Review } from "./review";
export const dynamic = "force-dynamic";
export const metadata: Metadata = {
  title: "Подтверждение демобронирования",
  robots: { index: false, follow: false },
};
export default async function ReviewPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const selection = selectionFromQuery(await searchParams);
  if (!selection)
    return (
      <section className="wrap panel stack">
        <h1>Проверьте выбор комнаты</h1>
        <p>Для расчёта нужны комната, обе даты и количество гостей.</p>
        <Link className="button" href="/demo/hotels">
          Вернуться к каталогу
        </Link>
      </section>
    );
  return (
    <div className="wrap">
      <Review selection={selection} />
    </div>
  );
}
