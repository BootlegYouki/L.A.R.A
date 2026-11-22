import { ImportError, parseRoster } from './learnerImport';

self.onmessage = (event: MessageEvent<{ bytes: ArrayBuffer; filename: string }>) => {
  try { self.postMessage({ rows: parseRoster(event.data.bytes, event.data.filename) }); }
  catch (error) { self.postMessage({ issue: error instanceof ImportError ? error.issue : 'file' }); }
};
