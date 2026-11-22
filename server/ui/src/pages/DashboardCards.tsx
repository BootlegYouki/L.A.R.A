import { useRef, useState, type ReactNode } from 'react';
import { useStrings } from '../i18n';
import { LAYOUT_KEY, moveCard, normalizeOrder } from '../dashboardLayout';

export type DashboardItem = { id: string; label: string; size: 'comparison' | 'compact' | 'summary' | 'half' | 'graph' | 'full'; content: ReactNode };

export default function DashboardCards({ items }: { items: DashboardItem[] }) {
  const { t } = useStrings();
  const ids = items.map(item => item.id);
  const [saved, setSaved] = useState<string[]>(() => {
    try { return normalizeOrder(JSON.parse(localStorage.getItem(LAYOUT_KEY) ?? 'null'), ids); }
    catch { return [...ids]; }
  });
  const [editing, setEditing] = useState(false);
  const [dragged, setDragged] = useState<string | null>(null);
  const [over, setOver] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const arrangeButton = useRef<HTMLButtonElement>(null);
  const handles = useRef<Record<string, HTMLButtonElement | null>>({});
  const order = normalizeOrder(saved, ids);
  const byId = new Map(items.map(item => [item.id, item]));
  const defaults = order.every((id, i) => ids[i] === id);

  function save(next: string[], message: string, focus?: string, reset = false) {
    setSaved(next);
    try {
      if (reset) localStorage.removeItem(LAYOUT_KEY);
      else localStorage.setItem(LAYOUT_KEY, JSON.stringify(next));
      setNotice(message);
    } catch { setNotice(`${message} ${t.monitor_layout_session}`); }
    if (focus) requestAnimationFrame(() => handles.current[focus]?.focus());
  }
  function move(id: string, target: string, focus = false) {
    if (id === target) return;
    const next = moveCard(order, id, target);
    const label = byId.get(id)?.label ?? '';
    save(next, t.monitor_card_moved.replace('{name}', label).replace('{position}', String(next.indexOf(id) + 1)).replace('{count}', String(next.length)), focus ? id : undefined);
  }

  return <section className="dashboard-layout" aria-label={t.monitor_dashboard_cards}>
    <div className="dashboard-layout-toolbar"><p>{editing ? t.monitor_layout_help : t.monitor_layout_hint}</p><div>
      <button type="button" ref={arrangeButton} className="admin-secondary" aria-pressed={editing} onClick={() => { setEditing(value => !value); setDragged(null); setOver(null); }}><i className="ph ph-dots-six" aria-hidden="true" />{editing ? t.monitor_layout_done : t.monitor_layout_arrange}</button>
      <button type="button" className="admin-secondary" disabled={defaults} onClick={() => { save([...ids], t.monitor_layout_reset_done, undefined, true); setDragged(null); setOver(null); }}><i className="ph ph-arrow-counter-clockwise" aria-hidden="true" />{t.monitor_layout_reset}</button>
    </div></div>
    <p className="monitor-footnote" role="status" aria-live="polite" aria-atomic="true">{notice}</p>
    <div className={`dashboard-grid${editing ? ' dashboard-editing' : ''}`}>{order.map((id, index) => {
      const item = byId.get(id)!;
      return <div key={id} data-card-id={id} className={`dashboard-item dashboard-item-${item.size}${dragged === id ? ' dashboard-dragging' : ''}${over === id && dragged !== id ? ' dashboard-drop-target' : ''}`}
        onDragOver={event => { if (!dragged) return; event.preventDefault(); event.dataTransfer.dropEffect = 'move'; setOver(id); }}
        onDrop={event => { if (!dragged) return; event.preventDefault(); move(dragged, id); setDragged(null); setOver(null); }}>
        {editing && <div className="dashboard-card-tools"><button type="button" className="dashboard-drag-handle" ref={node => { handles.current[id] = node; }} draggable
          aria-label={t.monitor_move_card.replace('{name}', item.label)} aria-describedby="dashboard-drag-help"
          onDragStart={event => { setDragged(id); setOver(id); event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('application/x-lara-card', id); }}
          onDragEnd={() => { setDragged(null); setOver(null); }} onKeyDown={event => {
            const destination = event.key === 'Home' ? 0 : event.key === 'End' ? order.length - 1 : ['ArrowUp', 'ArrowLeft'].includes(event.key) ? index - 1 : ['ArrowDown', 'ArrowRight'].includes(event.key) ? index + 1 : -1;
            if (event.key === 'Escape') { setEditing(false); setDragged(null); setOver(null); requestAnimationFrame(() => arrangeButton.current?.focus()); return; }
            if (destination >= 0 && destination < order.length) { event.preventDefault(); move(id, order[destination], true); }
          }}><i className="ph ph-dots-six" aria-hidden="true" /><span>{item.label}</span></button>
          <button type="button" className="dashboard-move-button" disabled={index === 0} aria-label={t.monitor_move_earlier.replace('{name}', item.label)} onClick={() => move(id, order[index - 1], true)}><i className="ph ph-arrow-up" aria-hidden="true" /></button>
          <button type="button" className="dashboard-move-button" disabled={index === order.length - 1} aria-label={t.monitor_move_later.replace('{name}', item.label)} onClick={() => move(id, order[index + 1], true)}><i className="ph ph-arrow-down" aria-hidden="true" /></button>
        </div>}
        {item.content}
      </div>;
    })}</div>
    <p id="dashboard-drag-help" className="sr-only">{t.monitor_layout_keyboard}</p>
  </section>;
}
