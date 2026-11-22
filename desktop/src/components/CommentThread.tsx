import {useEffect,useRef,useState,type FormEvent} from 'react';
import {t} from '../i18n';
import type {CachedAccount} from '../services/auth';
import {readThread,sendComment,markPrivateCommentsRead,type ThreadData,type ThreadTarget} from '../services/comments';
interface Props {account:CachedAccount;target:ThreadTarget;offline:boolean;onRefresh:()=>Promise<void>;onCountChanged?:(count:number)=>void;onRead?:()=>void}
export default function CommentThread(props:Props) {
 const {account,target}=props;
 // Remount the draft and pending requests whenever the account or private recipient changes.
 const key=`${account.hub.ip}:${account.hub.http_port}/${account.user.id}/${target.kind}/${target.id}/${target.kind==='assignment'?target.student_id??account.user.id:''}`;
 return <Thread key={key} {...props}/>;
}
function Thread({account,target,offline,onRefresh,onCountChanged,onRead}:Props) {
 const [thread,setThread]=useState<ThreadData|null>(null),[loadError,setLoadError]=useState(false),[retry,setRetry]=useState(0);
 const [draft,setDraft]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const pendingId=useRef<string|undefined>(undefined),sending=useRef(false),readEpoch=useRef(0),request=useRef<AbortController|null>(null);
 const latest=useRef({account,target,onRefresh});latest.current={account,target,onRefresh};
 const needsSync=useRef(false),refreshing=useRef(false);
 const refresh=()=>{needsSync.current=true;setRetry(value=>value+1)};
 const privateThread=target.kind==='assignment',heading=privateThread?t.comments_private:t.comments_class;
 const lastRead=useRef(''),readAlive=useRef(true),readCallback=useRef(onRead);readCallback.current=onRead;
 useEffect(()=>{readAlive.current=true;return()=>{readAlive.current=false}},[]);
 useEffect(()=>{
  const {account,target}=latest.current;
  if(!thread||account.user.role!=='TEACHER'||target.kind!=='assignment'||typeof document==='undefined'||document.visibilityState!=='visible')return;
  const ids=thread.items.filter(item=>item.author_id===target.student_id&&item.author_id!==account.user.id&&item.sync_status!=='REJECTED').map(item=>item.id);
  const key=ids.join(',');if(!key||lastRead.current===key)return;lastRead.current=key;
  void markPrivateCommentsRead(account,target,ids).then(()=>{if(readAlive.current&&lastRead.current===key)readCallback.current?.()}).catch(()=>{if(lastRead.current===key)lastRead.current=''});
 },[thread]);
 useEffect(()=>{
  let current=true;const epoch=++readEpoch.current,fetch=needsSync.current;needsSync.current=false;refreshing.current=true;
  void (async()=>{try{if(fetch&&!offline)await latest.current.onRefresh();if(!current)return;const value=await readThread(latest.current.account,latest.current.target);if(current&&epoch===readEpoch.current){setThread(value);setLoadError(false)}}catch{if(current&&epoch===readEpoch.current)setLoadError(true)}finally{if(current&&epoch===readEpoch.current)refreshing.current=false}})();
  return()=>{current=false};
 },[account,offline,retry]);
 useEffect(()=>()=>request.current?.abort(),[]);
 const countChanged=useRef(onCountChanged);countChanged.current=onCountChanged;
 useEffect(()=>{if(thread)countChanged.current?.(thread.items.length)},[thread]);
 useEffect(()=>{
  if(offline)return;
  const timer=setInterval(()=>{if(document.visibilityState==='visible'&&!sending.current&&!refreshing.current){needsSync.current=true;setRetry(value=>value+1)}},5000);
  return()=>clearInterval(timer);
 },[offline]);
 const teacherOffline=privateThread&&account.user.role==='TEACHER'&&offline;
 const readonly=thread?.write_mode==='readonly';
 async function submit(event:FormEvent) {
  event.preventDefault();if(sending.current||!thread||readonly||teacherOffline||!draft.trim())return;
  sending.current=true;setBusy(true);setError('');readEpoch.current++;request.current=new AbortController();
  pendingId.current??=crypto.randomUUID();
  try {const next=await sendComment(account,target,pendingId.current,draft,offline,request.current.signal);if(request.current.signal.aborted)return;if(next)setThread(next);setDraft('');pendingId.current=undefined;refresh()}
  catch(failure){if(request.current?.signal.aborted)return;const code=failure instanceof Error?failure.message:String(failure);setError(code==='comment_invalid'?t.comments_invalid:code==='comment_read_only'?t.comments_readonly:code==='comment_online_required'?t.comments_online:privateThread&&account.user.role==='TEACHER'?t.comments_uncertain:t.comments_send_error);refresh()}
  finally {sending.current=false;if(!request.current?.signal.aborted)setBusy(false)}
 }
 return <section className={'comment-thread'+(privateThread?' private-thread':'')} aria-label={heading}>
  <h2><i className={'ph '+(privateThread?'ph-lock-simple':'ph-chat-circle-dots')} aria-hidden="true"/>{heading}<button type="button" className="icon-button comment-refresh" aria-label={t.comments_refresh} onClick={refresh}><i className="ph ph-arrow-clockwise" aria-hidden="true"/></button></h2>
  {privateThread&&<p className="comment-help">{t.comments_private_help}</p>}
  {loadError&&<p role="alert">{t.comments_load_error}<button type="button" className="control text-button" onClick={refresh}>{t.retry}</button></p>}
  {!thread&&!loadError&&<p role="status">{t.comments_loading}</p>}
  {thread&&<div className="comment-list" role="log" aria-label={heading} aria-live="polite">{thread.items.length?thread.items.map(item=><article className="comment-item" key={item.id}><span className="comment-avatar" aria-hidden="true"><i className="ph ph-user"/></span><div className="comment-message"><header><strong>{item.author_id===account.user.id?t.comments_you:item.author_name??t.comments_member}</strong><time dateTime={new Date(item.created_at).toISOString()}>{new Date(item.created_at).toLocaleString('en',{month:'short',day:'numeric',hour:'numeric',minute:'2-digit'})}</time></header><p>{item.content}</p><small className={item.sync_status==='REJECTED'?'comment-rejected':'comment-state'}>{item.sync_status==='QUEUED_FOR_SYNC'?t.comments_queued:item.sync_status==='REJECTED'?t.comments_rejected:t.comments_sent}</small></div></article>):<p className="comment-help">{t.comments_empty}</p>}</div>}
  {readonly&&<p className="comment-help">{t.comments_readonly}</p>}{teacherOffline&&<p className="comment-help">{t.comments_online}</p>}
  <form onSubmit={event=>{void submit(event)}}><textarea rows={2} maxLength={10000} aria-label={privateThread?t.comments_private_placeholder:t.comments_placeholder} placeholder={privateThread?t.comments_private_placeholder:t.comments_placeholder} value={draft} disabled={busy||!thread||readonly||teacherOffline} onChange={event=>{setDraft(event.target.value);setError('');pendingId.current=undefined}}/><button type="submit" className="control tonal-button" disabled={busy||!thread||readonly||teacherOffline||!draft.trim()}><i className="ph ph-paper-plane-right" aria-hidden="true"/>{busy?t.comments_sending:t.comments_send}</button></form>
  {error&&<p className="comment-error" role="alert">{error}</p>}
 </section>;
}
