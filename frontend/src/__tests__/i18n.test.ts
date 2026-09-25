import { describe, expect, it } from 'vitest';
import en from '../i18n/locales/en.json';
import de from '../i18n/locales/de.json';

const sources = import.meta.glob(['../**/*.{ts,tsx}', '!../__tests__/**'], { query: '?raw', import: 'default', eager: true }) as Record<string, string>;

const flatten = (obj: Record<string, unknown>, prefix = ''): string[] =>
  Object.entries(obj).flatMap(([key, value]) =>
    value && typeof value === 'object' ? flatten(value as Record<string, unknown>, `${prefix}${key}.`) : [`${prefix}${key}`]);

const usedKeys = [...new Set(Object.values(sources).flatMap((code) =>
  [...code.matchAll(/\bt\(\s*['"]([a-zA-Z0-9_.]+)['"]/g)].map((m) => m[1])))];

describe('translations', () => {
  it('finds the keys used in the code', () => {
    expect(usedKeys.length).toBeGreaterThan(100);
  });

  it.each([['en', en], ['de', de]])('%s defines every key used in the code', (_, messages) => {
    const defined = new Set(flatten(messages));
    expect(usedKeys.filter((key) => !defined.has(key))).toEqual([]);
  });

  it('keys carry no stray punctuation', () => {
    expect(flatten(en).filter((key) => /[:\s]/.test(key))).toEqual([]);
  });
});
