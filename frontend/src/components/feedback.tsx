import type { ReactNode } from "react";

type FeedbackProps = {
  title: string;
  children?: ReactNode;
  tone?: "error" | "info";
};

export function Feedback({ title, children, tone = "info" }: FeedbackProps) {
  return (
    <section
      className={`feedback${tone === "error" ? " error" : ""}`}
      role={tone === "error" ? "alert" : "status"}
      aria-live={tone === "error" ? "assertive" : "polite"}
    >
      <span className="feedback-mark" aria-hidden="true">
        {tone === "error" ? "!" : "i"}
      </span>
      <div className="feedback-copy">
        <h2>{title}</h2>
        {children ? <div>{children}</div> : null}
      </div>
    </section>
  );
}
