/**
 * Minimal RFC 4180 CSV parser for metadata imports: quoted fields, escaped quotes, CRLF,
 * and the separator detected from the header line (`;` is common in German exports).
 */
export function parseCsv(text: string): { columns: string[]; rows: Record<string, string>[] } {
  const clean = text.replace(/^\uFEFF/, '');
  const firstLine = clean.split(/\r?\n/, 1)[0] ?? '';
  const separator = [';', ',', '\t'].reduce((best, sep) =>
    firstLine.split(sep).length > firstLine.split(best).length ? sep : best, ',');

  const lines: string[][] = [];
  let field = '';
  let row: string[] = [];
  let quoted = false;
  for (let i = 0; i < clean.length; i++) {
    const c = clean[i];
    if (quoted) {
      if (c === '"' && clean[i + 1] === '"') { field += '"'; i++; }
      else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"') quoted = true;
    else if (c === separator) { row.push(field); field = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && clean[i + 1] === '\n') i++;
      row.push(field); field = '';
      if (row.some((v) => v !== '')) lines.push(row);
      row = [];
    } else field += c;
  }
  row.push(field);
  if (row.some((v) => v !== '')) lines.push(row);

  const [header = [], ...body] = lines;
  const columns = header.map((h) => h.trim());
  return {
    columns,
    rows: body.map((values) => Object.fromEntries(columns.map((col, idx) => [col, (values[idx] ?? '').trim()]))),
  };
}
