import {invoke} from '@tauri-apps/api/core';
import DOMPurify from 'dompurify';
import type {CachedAccount} from './auth';
export type DocumentSource={kind:'submission';id:string;attachment_index:number}|{kind:'material';id:string};
export const isDocumentMime=(mime:string)=>mime==='application/pdf'||mime==='application/vnd.openxmlformats-officedocument.wordprocessingml.document';
export async function loadDocument(account:CachedAccount,source:DocumentSource,offline:boolean):Promise<{mime:string;data:ArrayBuffer}>{
 const args={hub:account.hub,userId:account.user.id,source};
 const info=await invoke<{mime:string;size:number}>('prepare_document',{...args,offline});
 if(!isDocumentMime(info.mime)||!Number.isSafeInteger(info.size)||info.size<=0||info.size>64*1024*1024)throw new Error('invalid_document');
 const data=await invoke<ArrayBuffer>('read_document',args);
 if(!(data instanceof ArrayBuffer)||data.byteLength!==info.size)throw new Error('invalid_document');
 return {mime:info.mime,data};
}
export function sanitizeWordPreview(html:string):string {
 return DOMPurify.sanitize(html,{ALLOWED_TAGS:['p','br','h1','h2','h3','h4','h5','h6','strong','em','u','s','sup','sub','ul','ol','li','table','thead','tbody','tr','td','th','img','span','div'],ALLOWED_ATTR:['src','alt','colspan','rowspan'],ALLOW_DATA_ATTR:false});
}
export async function renderWordPreview(data:ArrayBuffer):Promise<string>{
 const {default:mammoth}=await import('mammoth');
 const result=await mammoth.convertToHtml({arrayBuffer:data},{externalFileAccess:false,includeEmbeddedStyleMap:false,convertImage:mammoth.images.imgElement(async image=>({src:['image/png','image/jpeg','image/webp','image/gif'].includes(image.contentType)?`data:${image.contentType};base64,${await image.readAsBase64String()}`:''}))});
 return sanitizeWordPreview(result.value);
}

export function pdfPageScale(pageWidth:number,pageHeight:number,availableWidth:number,zoom:number):number {
 if(![pageWidth,pageHeight,availableWidth,zoom].every(value=>Number.isFinite(value)&&value>0))throw new Error('invalid_document');
 const scale=Math.min(availableWidth/pageWidth*zoom,4096/pageWidth,8192/pageHeight,Math.sqrt(16*1024*1024/(pageWidth*pageHeight)));
 if(!Number.isFinite(scale)||scale<=0)throw new Error('invalid_document');
 return scale;
}
