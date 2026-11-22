import { useEffect, useRef, type ReactNode } from 'react';

export default function Dialog({ title, close, children, busy = false, wide = false }: {
  title: string; close: () => void; children: ReactNode; busy?: boolean; wide?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const previous = document.activeElement;
    ref.current?.showModal();
    const dialog = ref.current;
    return () => {
      dialog?.close();
      if (previous instanceof HTMLElement) previous.focus();
    };
  }, []);
  return <dialog ref={ref} aria-labelledby="dialog-title" className={`admin-dialog${wide ? ' max-w-4xl' : ''}`} onCancel={event => {
    event.preventDefault();
    if (!busy) close();
  }}>
    <h2 id="dialog-title" className="mb-4 text-xl font-black">{title}</h2>
    {children}
  </dialog>;
}
