import Link from "next/link";
import { pageHref, type SearchValues } from "@/lib/search";
export function Pagination({
  path,
  values,
  page,
  totalPages,
}: {
  path: string;
  values: SearchValues;
  page: number;
  totalPages: number;
}) {
  if (!totalPages && !page) return null;
  return (
    <nav className="pagination" aria-label="Страницы результатов">
      {page > 0 && (
        <Link
          className="button secondary"
          href={pageHref(path, values, page - 1)}
        >
          ← Предыдущая
        </Link>
      )}
      <span>
        Страница {page + 1}
        {totalPages > 0 ? ` из ${totalPages}` : ""}
      </span>
      {page + 1 < totalPages && (
        <Link
          className="button secondary"
          href={pageHref(path, values, page + 1)}
        >
          Следующая →
        </Link>
      )}
    </nav>
  );
}
