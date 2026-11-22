import {useEffect,useRef,useState} from 'react';
import type {CachedAccount} from '../services/auth';
import {queueHomework,readHomework,flushHomework,type HomeworkStatus} from '../services/homework';
import {t} from '../i18n';
interface Props {account:CachedAccount;assignmentId:string;offline:boolean;archived:boolean;onChanged:()=>void}
export default function HomeworkUpload(props:Props){return <Upload key={`${props.account.hub.ip}:${props.account.hub.http_port}/${props.account.user.id}/${props.assignmentId}`} {...props}/>}
function Upload({account,assignmentId,offline,archived,onChanged}:Props){
 const [file,setFile]=useState<File|null>(null),[status,setStatus]=useState<HomeworkStatus|null>(null),[loading,setLoading]=useState(true),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const fileInput=useRef<HTMLInputElement>(null);
 const [thumbnail,setThumbnail]=useState<string|null>(null);
 useEffect(()=>{setThumbnail(null);if(!file||file.size>8*1024*1024||!['image/png','image/jpeg'].includes(file.type))return;const reader=new FileReader();reader.onload=()=>{if(typeof reader.result==='string')setThumbnail(reader.result);};reader.readAsDataURL(file);return()=>{reader.onload=null;if(reader.readyState===FileReader.LOADING)reader.abort();};},[file]);
 const sending=useRef(false),alive=useRef(true),latest=useRef(account),changed=useRef(onChanged);latest.current=account;changed.current=onChanged;
 useEffect(()=>{alive.current=true;let reading=false;const refresh=async()=>{if(reading)return;reading=true;try{const value=await readHomework(latest.current,assignmentId);if(alive.current){setStatus(value);setLoading(false);}}catch{if(alive.current){setError(t.homework_read_error);setLoading(false);}}finally{reading=false}};void refresh();const timer=setInterval(()=>void refresh(),2000);return()=>{alive.current=false;clearInterval(timer)}},[assignmentId]);
 async function submit(){
  if(sending.current||!file||archived||status?.status==='QUEUED_FOR_SYNC')return;
  sending.current=true;setBusy(true);setError('');
  try{await queueHomework(account,assignmentId,file);if(!alive.current)return;setFile(null);if(fileInput.current)fileInput.current.value='';setStatus(await readHomework(account,assignmentId));changed.current();if(!offline){void flushHomework(account).then(()=>{if(alive.current)changed.current()}).catch(()=>{});}}
  catch{if(alive.current)setError(t.homework_queue_error)}finally{sending.current=false;if(alive.current)setBusy(false)}
 }
 return <section className="homework-upload" aria-label={t.homework_your_work}>
  {loading&&<p role="status">{t.loading}</p>}
  {status&&<p role="status">{status.status==='QUEUED_FOR_SYNC'?t.homework_queued:status.status==='SYNCED'?t.homework_submitted:status.reason==='LATE_SUBMISSION'?t.homework_late:status.reason==='ALREADY_GRADED'?t.homework_graded:t.homework_rejected}</p>}
  <input ref={fileInput} className="homework-hidden-input" aria-label={t.homework_file} type="file" accept=".jpg,.jpeg,.png,.pdf,.docx" disabled={busy||archived||loading||status?.status==='QUEUED_FOR_SYNC'} onChange={e=>{setFile(e.target.files?.[0]??null);setError('')}}/>
  {file&&<div className="homework-selected-file"><div className="homework-file-card"><div><strong title={file.name}>{file.name}</strong><small>{file.type.startsWith('image/')?t.homework_image:t.homework_document}</small></div>{thumbnail?<img src={thumbnail} alt=""/>:<span className="homework-file-symbol"><i className="ph ph-file-text" aria-hidden="true"/></span>}</div><button type="button" className="icon-button homework-remove" aria-label={t.homework_remove_attachment} disabled={busy} onClick={()=>{setFile(null);setError('');if(fileInput.current)fileInput.current.value='';}}><i className="ph ph-x" aria-hidden="true"/></button></div>}
  <button type="button" className="control homework-add-file" disabled={busy||archived||loading||status?.status==='QUEUED_FOR_SYNC'} onClick={()=>fileInput.current?.click()}><i className="ph ph-plus" aria-hidden="true"/>{t.homework_add_attachment}</button>
  <button type="button" className="control auth-submit homework-turn-in" aria-describedby={!file?'homework-empty-help':undefined} disabled={busy||archived||loading||!file||status?.status==='QUEUED_FOR_SYNC'} onClick={()=>void submit()}>{busy?t.homework_saving:file?t.homework_turn_in:t.homework_mark_done}</button>
  {!file&&status?.status!=='SYNCED'&&status?.status!=='QUEUED_FOR_SYNC'&&<p id="homework-empty-help" className="homework-empty-help">{t.homework_attachment_required}</p>}
  {error&&<p role="alert">{error}</p>}
 </section>;
}
