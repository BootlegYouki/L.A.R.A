import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { setNonce } from 'get-nonce';
import App from './App';
import './index.css';
import {readHubPreference} from './services/settings';
import {installWebSocketPolicy} from './services/websocket-policy';

// Tauri replaces this placeholder per document; Radix must share that style nonce.
const nonce = document.querySelector<HTMLMetaElement>('meta[name="lara-style-nonce"]')?.content;
if (nonce && nonce !== '__TAURI_STYLE_NONCE__') setNonce(nonce);
installWebSocketPolicy(readHubPreference());
const root = document.getElementById('root');
if (root) createRoot(root).render(<StrictMode><App /></StrictMode>);
