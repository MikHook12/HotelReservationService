import type { Metadata } from "next";
import { SiteHeader } from "@/components/site-header";
import { SiteFooter } from "@/components/site-footer";
import { DemoNotice } from "@/components/demo-notice";
import { qaMode } from "@/lib/backend-config";
import "./globals.css";
import "./stage.css";
import "./booking.css";
export const metadata: Metadata = {
  title: {
    default: "Hotel Reservation — путешествие начинается с выбора",
    template: "%s · Hotel Reservation",
  },
  description:
    "Демонстрационный проект бронирования: вымышленные отели, без оплаты и реального заселения.",
};
export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ru" data-scroll-behavior="smooth">
      <body>
        <a className="skip-link" href="#main">
          К содержимому
        </a>
        {qaMode() && (
          <div className="qa-notice">
            Проверка интерфейса: тестовый API. Эти данные не подтверждают
            подключение к Spring Boot.
          </div>
        )}
        <SiteHeader />
        <DemoNotice />
        <main id="main" tabIndex={-1}>
          {children}
        </main>
        <SiteFooter />
      </body>
    </html>
  );
}
