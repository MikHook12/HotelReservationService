// Format decimal strings without a floating-point price calculation.
export function rubles(value: string): string {
  if (!/^\d+\.\d{2}$/.test(value)) return "Цена уточняется";
  const [whole, fraction] = value.split(".");
  return `${BigInt(whole).toLocaleString("ru-RU")}${fraction === "00" ? "" : `,${fraction}`} ₽`;
}

export function nightsLabel(count: number): string {
  const form = new Intl.PluralRules("ru").select(count);
  return `${count} ${form === "one" ? "ночь" : form === "few" ? "ночи" : "ночей"}`;
}
