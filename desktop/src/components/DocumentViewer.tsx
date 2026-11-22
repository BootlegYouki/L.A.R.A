import {useEffect,useRef,useState} from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import type {PDFDocumentProxy,RenderTask} from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import {loadDocument,renderWordPreview,pdfPageScale,type DocumentSource} from '../services/documents';
import type {CachedAccount} from '../services/auth';
import {t} from '../i18n';
import './DocumentViewer.css';
interface Props {account:CachedAccount;source:DocumentSource;title:string;offline:boolean;onClose:()=>void}
export default function DocumentViewer({account,source,title,offline,onClose}:Props){
 const [pdf,setPdf]=useState<PDFDocumentProxy|null>(null),[html,setHtml]=useState<string|null>(null),[error,setError]=useState('');
 const [page,setPage]=useState(1),[zoom,setZoom]=useState(1),[width,setWidth]=useState(700),[rendering,setRendering]=useState(false);
 const canvas=useRef<HTMLCanvasElement>(null),stage=useRef<HTMLDivElement>(null),opener=useRef(document.activeElement as HTMLElement|null);
 const latest=useRef({account,source,offline});latest.current={account,source,offline};
 const identity=`${account.hub.ip}:${account.hub.http_port}/${account.user.id}/${source.kind}/${source.id}/${source.kind==='submission'?source.attachment_index:0}`;
 useEffect(()=>{
  let active=true,dispose:(()=>void)|undefined;setPdf(null);setHtml(null);setError('');setPage(1);setZoom(1);
  void (async()=>{try{
   const {account,source,offline}=latest.current;const file=await loadDocument(account,source,offline);if(!active)return;
   if(file.mime==='application/pdf'){
    const library=await import('pdfjs-dist');if(!active)return;
    library.GlobalWorkerOptions.workerSrc=workerUrl;
    const task=library.getDocument({data:new Uint8Array(file.data),disableFontFace:true,useWasm:false,maxImageSize:16*1024*1024,canvasMaxAreaInBytes:64*1024*1024,standardFontDataUrl:new URL('pdf-fonts/',document.baseURI).href});
    dispose=()=>{void task.destroy().catch(()=>{})};const doc=await task.promise;if(active)setPdf(doc);
   }else{const result=await renderWordPreview(file.data);if(active)setHtml(result)}
  }catch(failure){if(active)setError(String(failure).includes('document_too_large')?t.document_too_large:t.document_error)}})();
  return()=>{active=false;dispose?.()};
 },[identity]);
 useEffect(()=>{const host=stage.current;if(!host)return;const observer=new ResizeObserver(()=>setWidth(Math.max(240,Math.min(900,host.clientWidth-48))));observer.observe(host);return()=>observer.disconnect()},[pdf,html]);
 useEffect(()=>{
  if(!pdf||!canvas.current)return;let active=true,task:RenderTask|undefined;setRendering(true);
  void pdf.getPage(page).then(value=>{if(!active||!canvas.current)return;const base=value.getViewport({scale:1});const viewport=value.getViewport({scale:pdfPageScale(base.width,base.height,width,zoom)});const element=canvas.current;const context=element.getContext('2d');if(!context)throw new Error('canvas_unavailable');element.width=Math.ceil(viewport.width);element.height=Math.ceil(viewport.height);task=value.render({canvas:element,canvasContext:context,viewport});return task.promise;}).then(()=>{if(active)setRendering(false)}).catch(()=>{if(active){setRendering(false);setError(t.document_error)}});
  return()=>{active=false;task?.cancel()};
 },[pdf,page,width,zoom]);
 return <Dialog.Root open onOpenChange={open=>{if(!open)onClose()}}><Dialog.Portal><Dialog.Overlay className="join-scrim"/><Dialog.Content className="document-dialog" onCloseAutoFocus={event=>{event.preventDefault();opener.current?.focus()}}>
  <header className="document-header"><i className="ph ph-file-text" aria-hidden="true"/><Dialog.Title title={title}>{title}</Dialog.Title><Dialog.Close asChild><button type="button" className="icon-button" aria-label={t.attachment_close}><i className="ph ph-x" aria-hidden="true"/></button></Dialog.Close></header>
  <Dialog.Description className="sr-only">{t.document_view}</Dialog.Description>
  <div className="document-toolbar">
   {pdf&&<div className="document-pages"><button type="button" className="icon-button" aria-label={t.document_previous} disabled={page<=1} onClick={()=>setPage(value=>value-1)}><i className="ph ph-caret-left" aria-hidden="true"/></button><label>{t.document_page}<input type="number" min={1} max={pdf.numPages} value={page} onChange={event=>{const next=Number(event.target.value);if(Number.isInteger(next)&&next>=1&&next<=pdf.numPages)setPage(next)}}/></label><span>{t.document_of} {pdf.numPages}</span><button type="button" className="icon-button" aria-label={t.document_next} disabled={page>=pdf.numPages} onClick={()=>setPage(value=>value+1)}><i className="ph ph-caret-right" aria-hidden="true"/></button></div>}
   <div className="document-zoom"><button type="button" className="icon-button" aria-label={t.document_zoom_out} disabled={zoom<=.5} onClick={()=>setZoom(value=>value-.25)}><i className="ph ph-minus" aria-hidden="true"/></button><output>{Math.round(zoom*100)}%</output><button type="button" className="icon-button" aria-label={t.document_zoom_in} disabled={zoom>=2} onClick={()=>setZoom(value=>value+.25)}><i className="ph ph-plus" aria-hidden="true"/></button></div>
  </div>
  {html!==null&&<p className="document-note">{t.document_word_note}</p>}
  <div ref={stage} className="document-stage" aria-busy={(!pdf&&html===null&&!error)||rendering}>
   {!pdf&&html===null&&!error&&<p role="status">{t.document_loading}</p>}{error&&<p role="alert">{error}</p>}
   {pdf&&<canvas ref={canvas} aria-label={`${title}, ${t.document_page} ${page}`}/>}
   {html!==null&&<article className="word-document" data-zoom={Math.round(zoom*100)} dangerouslySetInnerHTML={{__html:html}}/>}
  </div>
 </Dialog.Content></Dialog.Portal></Dialog.Root>;
}
