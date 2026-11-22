import { useStrings } from '../i18n';
import type { Metrics } from '../monitoring';
import type { DashboardItem } from './DashboardCards';

export function useWorkloadCards({ value, memory, speed }: {
  value: Metrics | null; memory: (value?: number | null) => string; speed: (value?: number | null) => string;
}): DashboardItem[] {
  const { t } = useStrings();
  const count = (n?: number | null) => n == null || !Number.isFinite(n) ? t.monitor_unavailable : n.toLocaleString('en', { maximumFractionDigits: 1 });
  const ms = (n?: number | null) => n == null ? t.monitor_unavailable : t.monitor_ms.replace('{value}', count(n));
  const work = value?.workload;
  const usage = value?.storage_use;
  const storage = [[t.monitor_videos, usage?.videos], [t.monitor_documents, usage?.documents], [t.monitor_homework, usage?.homework], [t.monitor_database, usage?.database]] as const;
  const total = storage.reduce((sum, [, amount]) => sum + (amount ?? 0), 0);
  const metrics = [
    ['connected-apps', t.monitor_connected_apps, count(value?.connected_apps), t.monitor_connected_help],
    ['request-rate', t.monitor_request_rate, work ? t.monitor_per_second.replace('{value}', count(work.requests_per_second)) : t.monitor_unavailable, t.monitor_last_minute],
    ['response-time', t.monitor_response_time, ms(work?.average_response_ms), t.monitor_response_help],
    ['failed-requests', t.monitor_failed_requests, count(work?.failed_last_minute), t.monitor_last_minute],
  ];
  return [
    ...metrics.map(([id, label, reading, help]): DashboardItem => ({ id, label, size: 'summary', content: <div className="workload-summary-card"><dl><dt>{label}</dt><dd>{reading}</dd></dl><p>{help}</p></div> })),
    { id: 'documents', label: t.monitor_document_processing, size: 'half', content: <section className="workload-panel" aria-labelledby="documents-title"><h3 id="documents-title">{t.monitor_document_processing}</h3>
        <dl>{[[t.monitor_processing, work?.documents_processing], [t.monitor_waiting, work?.documents_waiting], [t.monitor_finished, work?.documents_finished], [t.monitor_failed_count, work?.documents_failed]].map(([label, n]) => <div key={label}><dt>{label}</dt><dd>{count(n as number | undefined)}</dd></div>)}</dl>
        <p>{t.monitor_document_scope}</p>
      </section> },
    { id: 'transfers', label: t.monitor_active_transfers, size: 'half', content: <section className="workload-panel" aria-labelledby="transfers-title"><h3 id="transfers-title">{t.monitor_active_transfers}</h3>
        <dl>{[[t.monitor_uploads, work?.uploads], [t.monitor_downloads, work?.downloads], [t.monitor_video_streams, work?.video_streams]].map(([label, n]) => <div key={label}><dt>{label}</dt><dd>{count(n as number | undefined)}</dd></div>)}</dl>
        <p>{t.monitor_transfers_scope}</p>
      </section> },
    { id: 'ai', label: t.monitor_ai_resources, size: 'half', content: <section className="workload-panel" aria-labelledby="ai-resource-title"><h3 id="ai-resource-title">{t.monitor_ai_resources}</h3>
        <p>{value == null ? t.monitor_unavailable : value.ai_running ? t.monitor_ai_running : t.monitor_ai_not_running}</p>
        <dl><div><dt>{t.monitor_cpu}</dt><dd>{value?.ai_cpu_percent == null ? t.monitor_unavailable : `${count(value.ai_cpu_percent)}%`}</dd></div>
          <div><dt>{t.monitor_ram}</dt><dd>{memory(value?.ai_ram)}</dd></div><div><dt>{t.monitor_ai_queue}</dt><dd>{count(value?.ai_waiting)}</dd></div></dl>
        {value?.ai_waiting == null && <p>{t.monitor_ai_queue_unavailable}</p>}
      </section> },
    { id: 'disk-activity', label: t.monitor_disk_activity, size: 'half', content: <section className="workload-panel" aria-labelledby="disk-activity-title"><h3 id="disk-activity-title">{t.monitor_disk_activity}</h3>
        <dl><div><dt>{t.monitor_disk_read}</dt><dd>{speed(value?.disk_read_per_second)}</dd></div><div><dt>{t.monitor_disk_write}</dt><dd>{speed(value?.disk_write_per_second)}</dd></div></dl>
        <p>{t.monitor_disk_activity_scope}</p>
      </section> },
    { id: 'storage', label: t.monitor_storage_use, size: 'full', content: <section className="workload-panel" aria-labelledby="storage-use-title"><h3 id="storage-use-title">{t.monitor_storage_use}</h3>
      {usage ? <><div className="storage-use-bars" aria-hidden="true">{storage.map(([label, amount], i) => <span key={label} className={`storage-segment-${i}`} style={{ flexGrow: total ? (amount ?? 0) / total : 0 }} />)}</div>
        <dl className="storage-use-values">{storage.map(([label, amount], i) => <div key={label}><dt><span className={`storage-dot storage-segment-${i}`} aria-hidden="true" />{label}</dt><dd>{memory(amount)}</dd></div>)}</dl>
        {total === 0 && <p>{t.monitor_storage_empty}</p>}</> : <p>{t.monitor_unavailable}</p>}
      <p>{t.monitor_storage_scope}</p>
    </section> },
  ];
}
