import type { Dictionary } from '../i18n';

export type BannerVariant = 'success' | 'warning' | 'danger' | 'info';

interface HubStatusBannerProps {
  t: Dictionary;
  variant: BannerVariant;
  hubName?: string;
  message?: string;
}

export default function HubStatusBanner({ t, variant, hubName, message }: HubStatusBannerProps) {
  const icons: Record<BannerVariant, string> = {
    success: 'ph-check-circle',
    warning: 'ph-warning',
    danger: 'ph-x-circle',
    info: 'ph-info',
  };

  const ariaLabels: Record<BannerVariant, string> = {
    success: t.status_success,
    warning: t.status_warning,
    danger: t.status_error,
    info: t.status_info,
  };

  const displayMessage = message || (variant === 'success' && hubName
    ? t.connected_to_hub.replace('{name}', hubName)
    : t.hub_offline);

  return (
    <div
      role="status"
      aria-label={ariaLabels[variant]}
      className={`hub-status-banner banner-${variant}`}
    >
      <i className={`ph ${icons[variant]}`} aria-hidden="true" />
      <p>{displayMessage}</p>
    </div>
  );
}
