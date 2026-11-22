export const LAYOUT_KEY = 'lara:overview-layout:v1';

export function normalizeOrder(value: unknown, ids: readonly string[]): string[] {
  const allowed = new Set(ids);
  const stored = Array.isArray(value) ? value.filter((id): id is string => typeof id === 'string' && allowed.has(id)) : [];
  return [...new Set([...stored, ...ids])];
}

export function moveCard(order: readonly string[], source: string, target: string): string[] {
  const from = order.indexOf(source);
  const to = order.indexOf(target);
  const next = [...order];
  if (from < 0 || to < 0 || from === to) return next;
  next.splice(from, 1);
  next.splice(to, 0, source);
  return next;
}
