import { Pipe, PipeTransform } from '@angular/core';
import { Money } from './models';

/**
 * The single place minor units become a display string.
 *
 * Currencies do not all have two decimal places — JPY and KRW have none, and
 * dividing those by 100 is wrong by two orders of magnitude. The exponent table
 * mirrors the one in the Python model so both sides agree.
 *
 * Nothing else in the app should divide a money amount. If a component reaches
 * for `parseFloat` on one of these, that is a bug.
 */
const EXPONENTS: Record<string, number> = {
  JPY: 0, KRW: 0, VND: 0, CLP: 0, ISK: 0,
  BHD: 3, KWD: 3, JOD: 3, OMR: 3, TND: 3,
};

export function minorExponent(currency: string): number {
  return EXPONENTS[currency?.toUpperCase()] ?? 2;
}

export function toMajor(money: Money): number {
  return money.amount / Math.pow(10, minorExponent(money.currency));
}

@Pipe({ name: 'money', standalone: true })
export class MoneyPipe implements PipeTransform {
  transform(
    value: Money | null | undefined,
    mode: 'full' | 'bare' | 'signed' = 'full',
  ): string {
    if (!value) return '—';
    const exp = minorExponent(value.currency);
    const major = value.amount / Math.pow(10, exp);
    const formatted = major.toLocaleString(undefined, {
      minimumFractionDigits: exp,
      maximumFractionDigits: exp,
    });
    if (mode === 'bare') return formatted;
    if (mode === 'signed') {
      const sign = value.amount > 0 ? '+' : '';
      return `${sign}${formatted} ${value.currency}`;
    }
    return `${formatted} ${value.currency}`;
  }
}

/** Formats ledger dates for scanning while keeping older years unambiguous. */
@Pipe({ name: 'shortDate', standalone: true })
export class ShortDatePipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (!value) return '—';
    const iso = value.slice(0, 10);
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
    if (!match) return iso;

    const [, yearText, monthText, dayText] = match;
    const year = Number(yearText);
    const month = Number(monthText);
    const day = Number(dayText);
    const date = new Date(year, month - 1, day, 12);
    if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) {
      return iso;
    }

    const now = new Date();
    const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
    const target = Date.UTC(year, month - 1, day);
    const days = Math.round((target - today) / 86_400_000);
    const locale = typeof document === 'undefined' ? 'en' : document.documentElement.lang || 'en';
    if (days === 0 || days === -1) {
      const relative = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(days, 'day');
      return relative.charAt(0).toLocaleUpperCase(locale) + relative.slice(1);
    }

    return new Intl.DateTimeFormat(locale, {
      weekday: 'short',
      day: 'numeric',
      month: 'short',
      ...(year === now.getFullYear() ? {} : { year: 'numeric' as const }),
    }).format(date).replace(',', '');
  }
}

/** Turns 'travel.passenger_name' into 'Passenger name'. */
@Pipe({ name: 'detailKey', standalone: true })
export class DetailKeyPipe implements PipeTransform {
  transform(key: string): string {
    const bare = key.includes('.') ? key.split('.').slice(1).join('.') : key;
    const spaced = bare.replace(/_/g, ' ');
    return spaced.charAt(0).toUpperCase() + spaced.slice(1);
  }
}
