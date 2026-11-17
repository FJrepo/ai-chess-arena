/** Canonical decimal spelling for budget comparisons without binary rounding. */
export function canonicalBudget(value: string): string {
  if (!value) return '';
  const [integer, fraction] = value.split('.');
  const whole = integer.replace(/^0+/, '') || '0';
  const cents = fraction?.replace(/0+$/, '');
  return cents ? whole + '.' + cents : whole;
}
