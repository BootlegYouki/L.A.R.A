import type { ReactNode } from 'react';

interface Props { image: string; title: string; body?: string; variant: 'home' | 'stream' | 'center'; children?: ReactNode }

export default function OwlEmptyState({ image, title, body, variant, children }: Props) {
  return <section className={`empty-state empty-${variant}`}>
    <img className="empty-owl" src={image} alt="" />
    <div><h2>{title}</h2>{body && <p>{body}</p>}{children}</div>
  </section>;
}
