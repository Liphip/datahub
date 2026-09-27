import { describe, expect, it } from 'vitest';
import { parseCsv } from '../lib/csv';

describe('parseCsv', () => {
  it('detects semicolons and handles quotes', () => {
    const { columns, rows } = parseCsv('﻿EQUNR;SERNR;Bemerkung\r\n10001;1ESY1;"Keller; links"\r\n10002;1ESY2;"sagt ""hallo"""\r\n');
    expect(columns).toEqual(['EQUNR', 'SERNR', 'Bemerkung']);
    expect(rows).toEqual([
      { EQUNR: '10001', SERNR: '1ESY1', Bemerkung: 'Keller; links' },
      { EQUNR: '10002', SERNR: '1ESY2', Bemerkung: 'sagt "hallo"' },
    ]);
  });

  it('reads comma files and skips empty lines', () => {
    expect(parseCsv('a,b\n1,2\n\n3,\n').rows).toEqual([{ a: '1', b: '2' }, { a: '3', b: '' }]);
  });
});
