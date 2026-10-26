import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '../../../design-system/assets/fonts/fonts.css';
import '../../../design-system/assets/phosphor/phosphor.css';
import './index.css';
import App from './App';

const root = document.getElementById('root');
if (root) {
  createRoot(root).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}
