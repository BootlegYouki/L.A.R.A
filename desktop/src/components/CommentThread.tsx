import {useEffect,useRef,useState,type FormEvent} from 'react';
import {t} from '../i18n';
import type {CachedAccount} from '../services/auth';
import {readThread,sendComment,type ThreadData,type ThreadTarget} from '../services/comments';
interface Props {account:CachedAccount;target:ThreadTarget;offline:boolean;onChanged:()=>void}
export default function CommentThread(props:Props) {
 const {account,target}=props;
 // Remount the draft and pending requests whenever the account or private recipient changes.
 const key=`${account.hub.ip}:${account.hub.http_port}/${account.user.id}/${target.kind}/${target.id}/${target.kind==='assignment'?target.student_id??account.user.id:''}`;
 return <Thread key={key} {...props}/>;
}
function Thread({account,target,offline,onChanged}:Props) {
 const [thread,setThread]=useState<ThreadData|null>(null),[loadError,setLoadError]=useState(false),[retry,setRetry]=useState(0);
 const [draft,setDraft]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[sent,setSent]=useState(false);
 const pendingId=useRef<string|undefined>(undefined),sending=useRef(false),readEpoch=useRef(0),request=useRef<AbortController|null>(null);
 const privateThread=target.kind==='assignment',heading=privateThread?t.comments_private:t.comments_class;
 useEffect(()=>{let current=true;const epoch=++readEpoch.current;setLoadError(false);void readThread(account,target).then(value=>{if(current&&epoch===readEpoch.current)setThread(value)}).catch(()=>{if(current)setLoadError(true)});return()=>{current=false}},[account,target,retry]);
 useEffect(()=>()=>request.current?.abort(),[]);
 const teacherOffline=privateThread&&account.user.role==='TEACHER'&&offline;
 const readonly=thread?.write_mode==='readonly';
 async function submit(event:FormEvent) {
  event.preventDefault();if(sending.current||!thread||readonly||teacherOffline||!draft.trim())return;
  sending.current=true;setBusy(true);setError('');setSent(false);readEpoch.current++;request.current=new AbortController();
  pendingId.current??=crypto.randomUUID();
  try {const next=await sendComment(account,target,pendingId.current,draft,offline,request.current.signal);if(request.current.signal.aborted)return;if(next)setThread(next);else setSent(true);setDraft('');pendingId.current=undefined;onChanged()}
  catch(failure){if(request.current?.signal.aborted)return;const code=failure instanceof Error?failure.message:String(failure);setError(code==='comment_invalid'?t.comments_invalid:code==='comment_read_only'?t.comments_readonly:code==='comment_online_required'?t.comments_online:privateThread&&account.user.role==='TEACHER'?t.comments_uncertain:t.comments_send_error);setRetry(value=>value+1);if(privateThread&&account.user.role==='TEACHER')onChanged()}
  finally {sending.current=false;if(!request.current?.signal.aborted)setBusy(false)}
 }
 return <section className={'comment-thread'+(privateThread?' private-thread':'')} aria-label={heading}>
  <h2><i className={'ph '+(privateThread?'ph-lock-simple':'ph-chat-circle-dots')} aria-hidden="true"/>{heading}<button type="button" className="icon-button comment-refresh" aria-label={t.comments_refresh} onClick={()=>{onChanged();setRetry(value=>value+1)}}><i className="ph ph-arrow-clockwise" aria-hidden="true"/></button></h2>
  {privateThread&&<p className="comment-help">{t.comments_private_help}</p>}
  {loadError&&<p role="alert">{t.comments_load_error}<button type="button" className="control text-button" onClick={()=>setRetry(value=>value+1)}>{t.retry}</button></p>}
  {!thread&&!loadError&&<p role="status">{t.comments_loading}</p>}
  {thread&&<div className="comment-list" role="log" aria-label={heading} aria-live="polite">{thread.items.length?thread.items.map(item=><article className="comment-item" key={item.id}><span className="comment-avatar" aria-hidden="true"><i className="ph ph-user"/></span><div className="comment-message"><header><strong>{item.author_id===account.user.id?t.comments_you:item.author_name??t.comments_member}</strong><time dateTime={new Date(item.created_at).toISOString()}>{new Date(item.created_at).toLocaleString('en',{month:'short',day:'numeric',hour:'numeric',minute:'2-digit'})}</time></header><p>{item.content}</p><small className={item.sync_status==='REJECTED'?'comment-rejected':'comment-state'}>{item.sync_status==='QUEUED_FOR_SYNC'?t.comments_queued:item.sync_status==='REJECTED'?t.comments_rejected:t.comments_sent}</small></div></article>):<p className="comment-help">{t.comments_empty}</p>}</div>}
  {readonly&&<p className="comment-help">{t.comments_readonly}</p>}{teacherOffline&&<p className="comment-help">{t.comments_online}</p>}
  <form onSubmit={event=>{void submit(event)}}><textarea rows={2} maxLength={10000} aria-label={privateThread?t.comments_private_placeholder:t.comments_placeholder} placeholder={privateThread?t.comments_private_placeholder:t.comments_placeholder} value={draft} disabled={busy||!thread||readonly||teacherOffline} onChange={event=>{setDraft(event.target.value);setError('');setSent(false);pendingId.current=undefined}}/><button type="submit" className="control tonal-button" disabled={busy||!thread||readonly||teacherOffline||!draft.trim()}><i className="ph ph-paper-plane-right" aria-hidden="true"/>{busy?t.comments_sending:t.comments_send}</button></form>
  {error&&<p className="comment-error" role="alert">{error}</p>}{sent&&<p role="status">{t.comments_sent}</p>}
 </section>;
}
