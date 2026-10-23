type PageIntroProps = {
  eyebrow?: string;
  title: string;
  description?: string;
};

export function PageIntro({ eyebrow, title, description }: PageIntroProps) {
  return (
    <header className="page-intro">
      {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
      <h1>{title}</h1>
      {description ? (
        <p className="page-intro-description">{description}</p>
      ) : null}
    </header>
  );
}
