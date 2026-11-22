import * as XLSX from 'xlsx';
import { HubError, type User } from './admin';

export const MAX_IMPORT_ROWS = 500;
export const MAX_IMPORT_BYTES = 5 * 1024 * 1024;
export type ImportIssue = 'file' | 'headers' | 'sheet' | 'limit' | 'empty' | 'lrn' | 'name' | 'formula' | 'duplicate';
export class ImportError extends Error {
  constructor(public issue: ImportIssue) { super(issue); }
}
export type ImportStatus = 'pending' | 'invalid' | 'skipped' | 'created' | 'uncertain' | 'failed';
export type ImportRow = { row: number; lrn: string; name: string; status: ImportStatus; issue?: ImportIssue; pin?: string };
export type ImportApi = { list: () => Promise<User[]>; create: (row: ImportRow, pin: string) => Promise<User> };

function cellText(cell: XLSX.CellObject | undefined): string {
  if (!cell || cell.v === undefined) return '';
  if (cell.t === 'n') {
    const value = String(cell.v);
    return /^\d{12}$/.test(value) ? value : (cell.w ?? value);
  }
  return String(cell.v);
}

export function parseRoster(bytes: ArrayBuffer, filename: string): ImportRow[] {
  const excel = /\.xlsx$/i.test(filename);
  if ((!excel && !/\.csv$/i.test(filename)) || bytes.byteLength > MAX_IMPORT_BYTES) throw new ImportError('file');
  let workbook: XLSX.WorkBook;
  try {
    workbook = XLSX.read(bytes, { type: 'array', raw: true, cellFormula: true });
  } catch { throw new ImportError('file'); }
  const sheet = workbook.Sheets[excel ? 'Learners' : workbook.SheetNames[0]];
  if (!sheet) throw new ImportError(excel ? 'sheet' : 'file');
  const range = XLSX.utils.decode_range(sheet['!fullref'] ?? sheet['!ref'] ?? 'A1');
  if (range.e.c > 1 || cellText(sheet.A1).trim() !== 'LRN' || cellText(sheet.B1).trim() !== 'Full name') {
    throw new ImportError('headers');
  }
  const populated = new Set<number>();
  for (const address of Object.keys(sheet)) {
    const match = /^[AB]([1-9]\d*)$/.exec(address);
    if (!match || Number(match[1]) === 1) continue;
    const cell = sheet[address] as XLSX.CellObject;
    if (cell.f || cellText(cell).trim()) populated.add(Number(match[1]));
    if (populated.size > MAX_IMPORT_ROWS) throw new ImportError('limit');
  }
  const rows: ImportRow[] = [];
  for (const rowNumber of [...populated].sort((a, b) => a - b)) {
    const lrn = cellText(sheet[`A${rowNumber}`]).trim();
    const name = cellText(sheet[`B${rowNumber}`]).trim();
    const cells = [sheet[`A${rowNumber}`], sheet[`B${rowNumber}`]] as (XLSX.CellObject | undefined)[];
    if (!lrn && !name && !cells.some(cell => cell?.f)) continue;
    const issue: ImportIssue | undefined = cells.some(cell => cell?.f) || /^[=+@-]/.test(lrn) || /^[=+@-]/.test(name)
      ? 'formula' : !/^\d{12}$/.test(lrn) ? 'lrn' : !name || name.length > 100 ? 'name' : undefined;
    rows.push({ row: rowNumber, lrn, name, status: issue ? 'invalid' : 'pending', ...(issue ? { issue } : {}) });
  }
  if (!rows.length) throw new ImportError('empty');
  const counts = new Map<string, number>();
  rows.forEach(row => counts.set(row.lrn, (counts.get(row.lrn) ?? 0) + 1));
  return rows.map(row => !row.issue && counts.get(row.lrn)! > 1 ? { ...row, status: 'invalid', issue: 'duplicate' } : row);
}

export function markExisting(rows: ImportRow[], users: User[]): ImportRow[] {
  const ids = new Set(users.map(user => user.lrn_or_id));
  return rows.map(row => row.status === 'pending' && ids.has(row.lrn) ? { ...row, status: 'skipped' } : row);
}

export function generatePin(used: Set<string>): string {
  const sample = new Uint32Array(1);
  for (;;) {
    crypto.getRandomValues(sample);
    // Rejection sampling avoids modulo bias in the six-digit space.
    if (sample[0] >= 4_294_000_000) continue;
    const pin = String(sample[0] % 1_000_000).padStart(6, '0');
    if (!used.has(pin)) { used.add(pin); return pin; }
  }
}

export async function createRoster(initial: ImportRow[], api: ImportApi,
  update: (rows: ImportRow[]) => void, active: () => boolean = () => true): Promise<ImportRow[]> {
  if (initial.some(row => row.status === 'invalid')) throw new ImportError('file');
  let rows = initial.map(row => ({ ...row }));
  const users = await api.list();
  if (!active()) return rows;
  const ids = new Set(users.map(user => user.lrn_or_id));
  rows = rows.map(row => {
    if (row.status === 'uncertain') return { ...row, status: ids.has(row.lrn) ? 'uncertain' : 'pending' };
    return row.status === 'pending' && ids.has(row.lrn) ? { ...row, status: 'skipped' } : row;
  });
  update(rows);
  const used = new Set(rows.flatMap(row => row.pin ? [row.pin] : []));
  for (let index = 0; index < rows.length && active(); index++) {
    if (rows[index].status !== 'pending') continue;
    const pin = rows[index].pin ?? generatePin(used);
    rows[index] = { ...rows[index], pin, status: 'uncertain' };
    update([...rows]);
    try {
      const saved = await api.create(rows[index], pin);
      if (saved.lrn_or_id !== rows[index].lrn || saved.role !== 'STUDENT') throw new HubError('INVALID_RESPONSE', 200);
      rows[index] = { ...rows[index], status: 'created' };
    } catch (error) {
      if (error instanceof HubError && error.code === 'ALREADY_EXISTS') rows[index] = { ...rows[index], status: 'skipped', pin: undefined };
      else {
        if (error instanceof HubError && error.status >= 400 && error.status < 500) {
          rows[index] = { ...rows[index], status: error.status === 401 || error.status === 403 ? 'pending' : 'failed', pin: undefined };
        }
        if (active()) update([...rows]);
        throw error;
      }
    }
    if (active()) update([...rows]);
  }
  return rows;
}

export function credentialCsv(rows: ImportRow[]): Uint8Array {
  const quote = (value: string) => `"${value.replace(/"/g, '""')}"`;
  const lines = [['Full name', 'LRN', 'PIN'], ...rows.filter(row => row.status === 'created' && row.pin)
    .map(row => [row.name, row.lrn, row.pin!])];
  return new TextEncoder().encode('\uFEFF' + lines.map(line => line.map(quote).join(',')).join('\r\n') + '\r\n');
}

export function rosterTemplate(excel: boolean): Uint8Array {
  if (!excel) return new TextEncoder().encode('\uFEFFLRN,Full name\r\n');
  const workbook = XLSX.utils.book_new();
  const sheet = XLSX.utils.aoa_to_sheet([['LRN', 'Full name']]);
  sheet['!cols'] = [{ wch: 18 }, { wch: 35 }];
  for (let row = 2; row <= MAX_IMPORT_ROWS + 1; row++) sheet[`A${row}`] = { t: 's', v: '', z: '@' };
  sheet['!ref'] = `A1:B${MAX_IMPORT_ROWS + 1}`;
  XLSX.utils.book_append_sheet(workbook, sheet, 'Learners');
  return new Uint8Array(XLSX.write(workbook, { type: 'array', bookType: 'xlsx' }));
}
