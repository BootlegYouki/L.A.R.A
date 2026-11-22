import {invoke} from '@tauri-apps/api/core';
import type {CachedAccount} from './auth';
export interface MediaSource {id:string;url:string;mime?:string}
const entries=new Map<string,{promise:Promise<MediaSource>;users:number;timer?:ReturnType<typeof setTimeout>}>();
let scope='';
export function clearMediaCache(){for(const entry of entries.values()){clearTimeout(entry.timer);void entry.promise.then(value=>invoke('close_material_preview',{id:value.id})).catch(()=>{});}entries.clear();scope='';}
export function acquireMedia(account:{hub:CachedAccount['hub'];user:Pick<CachedAccount['user'],'id'>},id:string,revision:number,mime?:string,offline=false){
 const owner=`${account.hub.ip}:${account.hub.http_port}/${account.user.id}`;
 if(scope&&scope!==owner)clearMediaCache();scope=owner;
 const key=`${owner}/${id}/${revision}`;
 let entry=entries.get(key);
 if(!entry&&offline)return {source:Promise.reject<MediaSource>(new Error('offline_preview')),release:()=>{}};
 if(!entry){entry={users:0,promise:invoke<MediaSource>('open_material_preview',{hub:account.hub,userId:account.user.id,materialId:id}).then(value=>{
  if(!value||typeof value.id!=='string'||!/^http:\/\/127[.]0[.]0[.]1:[0-9]+\/[a-z0-9-]+$/.test(value.url)||!/^(image\/(png|jpeg|webp|gif)|video\/(mp4|webm))$/.test(value.mime??mime??''))throw new Error('invalid_preview');
  return value;
 }).catch(error=>{if(entries.get(key)===entry)entries.delete(key);throw error;})};entries.set(key,entry);}
 clearTimeout(entry.timer);entry.users++;
 const current=entry;
 const discard=()=>{if(entries.get(key)!==current)return;entries.delete(key);void current.promise.then(value=>invoke('close_material_preview',{id:value.id})).catch(()=>{});};
 return {source:entry.promise,release:()=>{current.users--;if(current.users===0){current.timer=setTimeout(discard,5*60*1000);const idle=[...entries.entries()].filter(([,value])=>!value.users);for(const [oldKey,old] of idle.slice(0,Math.max(0,idle.length-8))){clearTimeout(old.timer);entries.delete(oldKey);void old.promise.then(value=>invoke('close_material_preview',{id:value.id})).catch(()=>{});}}}};
}
