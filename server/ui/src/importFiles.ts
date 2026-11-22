import { invoke, isTauri } from '@tauri-apps/api/core';

export async function saveImportFile(token: string, filename: string, bytes: Uint8Array, title: string): Promise<boolean> {
  if (isTauri()) return invoke<boolean>('save_import_file', { token, filename, bytes: Array.from(bytes), title });
  const url = URL.createObjectURL(new Blob([new Uint8Array(bytes)]));
  const link = document.createElement('a');
  link.href = url; link.download = filename; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  return true;
}
