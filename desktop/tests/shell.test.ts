import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, expect, it, vi } from 'vitest';
import App from '../src/App';
import type { CachedAccount } from '../src/services/auth';
const {state}=vi.hoisted(()=>({state:{status:'ready' as 'ready'|'loading'|'error',account:null as CachedAccount|null,loadAccount:vi.fn(),setAccount:vi.fn()}}));
vi.mock('../src/store/app',()=>({useAppStore:()=>state}));
beforeEach(()=>{state.status='ready';state.account=null});
const render=()=>renderToStaticMarkup(createElement(App));
it('offers sign-in and manual Hub entry without fake classes or enrollment',()=>{
 const html=render();expect(html).toContain('Sign in');expect(html).toContain('Hub IP address');expect(html).toContain('type="password"');
 expect(html).toContain('aria-current="page"');expect(html).not.toContain('Science 4');expect(html).not.toContain('next desktop steps');
});
it('renders English without a translation control',()=>{
 const html=render();expect(html).toContain('New learner?');expect(html).not.toContain('ph-translate');expect(html).not.toContain('language-button');
});
it('offers cache retry without deleting work if native account restore fails',()=>{
 state.status='error';const html=render();expect(html).toContain('role="alert"');expect(html).toContain('Your saved work has not been cleared.');expect(html).toContain('Try again');
});
it('announces native cache loading before sign-in or empty Home',()=>{
 state.status='loading';const html=render();expect(html).toContain('Opening your saved classes');expect(html).not.toContain('Add a class to get started');
});
it('does not render another Hub account even before its restore effect runs',()=>{
 state.account={hub:{name:'Other',ip:'192.168.1.99',http_port:8080,ws_port:8081},user:{id:'u',lrn_or_id:'secret',full_name:'Other account',role:'TEACHER'},expires_at:1,persistent:true,classrooms:[{id:'c',name:'PRIVATE_CLASS',section:'A',class_code:'SECRET',teacher_id:'u'}]};
 const html=render();expect(html).not.toContain('PRIVATE_CLASS');expect(html).not.toContain('Other account');expect(html).not.toContain('SECRET');expect(html).toContain('Sign in');
});
