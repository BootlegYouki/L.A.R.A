import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { expect, it } from 'vitest';
import HubDiscovery from '../src/components/HubDiscovery';
import { dictionaries } from '../src/i18n';
it('renders escaped Hub metadata, an unconnected candidate list and a manual fallback in both languages', () => {
  for (const language of ['en','fil'] as const) {
    const t=dictionaries[language];
    const html=renderToStaticMarkup(createElement(HubDiscovery,{ t, hubs:[{name:'<script>Room</script>',ip:'192.168.1.50',http_port:8080,ws_port:8081}],failed:false,searching:false }));
    expect(html).toContain('&lt;script&gt;Room&lt;/script&gt;');
    expect(html).toContain(t.manual_address);
    expect(html).toContain('192.168.1.50:8080');
    expect(html).toContain('id="hub-address"');
    expect(html).not.toContain('Connected to Hub');
  }
});
it('preserves manual entry during loading, empty and unavailable discovery', () => {
  const t=dictionaries.en;
  for(const [failed,searching,label] of [[false,true,t.discovery_loading],[false,false,t.discovery_empty],[true,false,t.discovery_error]] as const) {
    const html=renderToStaticMarkup(createElement(HubDiscovery,{t,hubs:[],failed,searching}));
    expect(html).toContain(label);
    expect(html).toContain('id="hub-address"');
  }
});
