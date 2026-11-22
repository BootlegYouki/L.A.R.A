import {useEffect,useState,type ReactNode} from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import {acquireMedia,type MediaSource} from '../services/mediaCache';
import type {CachedAccount} from '../services/auth';
import {t} from '../i18n';
interface Props {account:CachedAccount;id:string;title:string;mime?:string;offline:boolean;revision?:number;children:ReactNode}
export default function AttachmentMedia({account,id,title,mime,offline,revision=0,children}:Props){
 const [source,setSource]=useState<MediaSource|null>(null),[failed,setFailed]=useState(false);
 const supported=!mime||/^(image\/(png|jpeg|webp|gif)|video\/(mp4|webm))$/.test(mime);
 useEffect(()=>{let active=true;setSource(null);setFailed(false);
  if(!supported)return;
  const lease=acquireMedia(account,id,revision,mime,offline);
  void lease.source.then(value=>{if(active)setSource(value)}).catch(()=>{if(active)setFailed(true)});
  return()=>{active=false;lease.release();};
 },[account,id,revision,offline,supported,mime]);
 if(!supported||!source||failed)return <>{children}</>;
 if((source.mime??mime)?.startsWith('video/'))return <figure className="attachment-media"><video controls preload="none" src={source.url} aria-label={title} onError={()=>setFailed(true)}/><figcaption>{title}</figcaption></figure>;
 return <Dialog.Root><figure className="attachment-media"><Dialog.Trigger asChild><button type="button" className="attachment-image" aria-label={t.attachment_enlarge.replace('{title}',title)}><img src={source.url} alt={title} loading="lazy" onError={()=>setFailed(true)}/></button></Dialog.Trigger><figcaption>{title}</figcaption></figure><Dialog.Portal><Dialog.Overlay className="join-scrim"/><Dialog.Content className="join-dialog image-preview-dialog"><Dialog.Title>{title}</Dialog.Title><Dialog.Description className="sr-only">{t.attachment_image_preview}</Dialog.Description><img src={source.url} alt={title}/><Dialog.Close asChild><button className="control text-button">{t.attachment_close}</button></Dialog.Close></Dialog.Content></Dialog.Portal></Dialog.Root>;
}
