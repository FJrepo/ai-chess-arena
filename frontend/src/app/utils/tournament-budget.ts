export function isBudgetDecimal(value: string): boolean {
  return /^(?:\d{1,17}(?:\.\d{0,2})?|\.\d{1,2})$/.test(value);
}

/** Canonical decimal spelling for budget comparisons without binary rounding. */
export function canonicalBudget(value: string): string {
  if (!isBudgetDecimal(value)) return value;
  const [integer, fraction] = value.split('.');
  const whole = integer.replace(/^0+/, '') || '0';
  const cents = fraction?.replace(/0+$/, '');
  return cents ? whole + '.' + cents : whole;
}
