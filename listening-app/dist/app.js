import {formatTime,parseTime,parseTranscript,validateSegments,segmentBounds,randomChoice,safeUrl} from './core.js';
const $=id=>document.getElementById(id);
const escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let saved={};try{saved=JSON.parse(localStorage.getItem('cet6-pocket')||'{}');}catch{}
const state={view:'practice',mode:'transcript',translation:saved.translation??true,speed:saved.speed??1,favorites:saved.favorites||[],stats:saved.stats||{},positions:saved.positions||{},selected:saved.selected,loop:false};
let db,tracks=[],current,activeIndex=-1,boundary=null,audioUrl=null,randomItem=null,randomVisible=false,toastTimer,installPrompt,editTrackId=null,pendingBackup=null,lastAudioTime=0,lastStatsWrite=0;
const audio=$('audio');
function today(){const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;}
function persist(){try{localStorage.setItem('cet6-pocket',JSON.stringify({translation:state.translation,speed:state.speed,favorites:state.favorites,stats:state.stats,positions:state.positions,selected:current?.id}));}catch{toast('学习记录无法保存，请检查浏览器存储空间。');}}
function toast(message){$('toast').textContent=message;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,4200);}
function openDB(){return new Promise((resolve,reject)=>{const req=indexedDB.open('cet6-pocket',1);req.onupgradeneeded=()=>{const d=req.result;d.createObjectStore('tracks',{keyPath:'id'});d.createObjectStore('audio');};req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);});}
function storage(store,operation,key,value){return new Promise((resolve,reject)=>{if(!db)return reject(new Error('浏览器无法保存本地资料，请退出隐私模式再试。'));const tx=db.transaction(store,operation==='get'||operation==='getAll'?'readonly':'readwrite');const obj=tx.objectStore(store);const req=operation==='put'?(key===undefined?obj.put(value):obj.put(value,key)):obj[operation](key);let result;req.onsuccess=()=>result=req.result;tx.oncomplete=()=>resolve(result);tx.onerror=()=>reject(tx.error||req.error);tx.onabort=()=>reject(tx.error||new Error('资料保存失败。'));});}
function saveImportedTrack(track,blob){return new Promise((resolve,reject)=>{if(!db)return reject(new Error('本地存储不可用。'));const tx=db.transaction(['tracks','audio'],'readwrite');tx.objectStore('tracks').put(track);if(blob)tx.objectStore('audio').put(blob,track.id);tx.oncomplete=resolve;tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error||new Error('保存失败，可能是空间不足。'));});}
async function saveTrack(track){if(track.type!=='demo')await storage('tracks','put',undefined,track);else try{localStorage.setItem('cet6-demo-segments',JSON.stringify(track.segments));}catch{}}
function goto(view){state.view=view;document.querySelectorAll('.view').forEach(el=>el.hidden=el.id!==`view-${view}`);document.querySelectorAll('[data-view]').forEach(el=>{const on=el.dataset.view===view;el.classList.toggle('selected',on);on?el.setAttribute('aria-current','page'):el.removeAttribute('aria-current');});$('crumb').textContent=({practice:'精听练习',random:'碎片随听',library:'真题资料库',progress:'我的学习'})[view];if(view==='library')renderLibrary();if(view==='progress')renderProgress();window.scrollTo({top:0,behavior:'instant'});}
function renderTrack(){
  $('track-title').textContent=current.title;$('player-name').textContent=current.title;
  $('track-badge').textContent=current.type==='demo'?'功能体验 · 非真题':current.type==='source'?'六级真题 · 原声音频':'我的听力资料';
  $('track-subtitle').textContent=current.type==='demo'?'原创示例 · 合成语音 · 用于体验功能':(current.audioUrl?'在线音频 · 需联网':'本地原声音频 · 可离线播放');
  $('sentence-count').textContent=`原文 · ${current.segments.length} 句`;
  $('translation-toggle').checked=state.translation;$('speed').value=String(state.speed);renderTranscript();applyMode();
}
function renderTranscript(){
  $('transcript').innerHTML=current.segments.map((s,i)=>`<article class="sentence ${i===activeIndex?'active':''}" data-index="${i}"><span class="sentence-number">${String(i+1).padStart(2,'0')}</span><div class="sentence-content"><button class="sentence-play" data-sentence="${i}">${escape(s.text)}</button><p class="sentence-translation" ${state.translation?'':'hidden'}>${escape(s.translation||'暂无译文，可导入配套译文。')}</p><div class="sentence-meta">${s.start===null?'待校准时间':formatTime(s.start)}</div></div><button class="favorite-button ${isFavorite(current.id,i)?'saved':''}" data-favorite="${i}" aria-label="${isFavorite(current.id,i)?'取消收藏':'收藏句子'}" aria-pressed="${isFavorite(current.id,i)}">${isFavorite(current.id,i)?'★':'☆'}</button></article>`).join('');
}
function applyMode(){const blind=state.mode==='blind';$('blind-panel').hidden=!blind;$('transcript').hidden=blind||!current.segments.length;$('no-transcript').hidden=blind||!!current.segments.length;$('translation-toggle').closest('label').hidden=blind;['transcript','blind','sentence'].forEach(m=>$(`mode-${m}`).classList.toggle('active',state.mode===m));$('mode-tip').textContent=state.mode==='sentence'?'逐句听清楚，打开「循环」反复练这一句':blind?'先听大意，听完再核对原文':'点击已校准的句子，即可从这里开始听';}
function setMode(mode){state.mode=mode;applyMode();if(mode!=='sentence'&&boundary?.kind==='sentence'&&!state.loop)boundary=null;if(mode==='sentence'&&!audio.paused){const i=activeIndex>=0?activeIndex:0;const b=segmentBounds(current.segments,i,audio.duration);if(b)boundary={...b,index:i,kind:'sentence'};else {audio.pause();toast('请先校准句子时间，再使用单句精听。');}}}
async function selectTrack(track,{resume=true}={}){
  if(current?.id===track.id)return;
  if(current){state.positions[current.id]=audio.currentTime;persist();}
  audio.pause();boundary=null;state.loop=false;$('loop').setAttribute('aria-pressed','false');activeIndex=-1;randomItem=null;current=track;state.selected=track.id;
  if(audioUrl){URL.revokeObjectURL(audioUrl);audioUrl=null;}
  let source=track.type==='demo'?'./demo.wav':track.audioUrl;
  if(!source){const blob=await storage('audio','get',track.id);if(blob){audioUrl=URL.createObjectURL(blob);source=audioUrl;}}
  audio.src=source||'';audio.playbackRate=state.speed;audio.preservesPitch=true;audio.load();
  if(resume&&state.positions[track.id]>0){const position=state.positions[track.id];audio.addEventListener('loadedmetadata',()=>{if(current.id===track.id)audio.currentTime=Math.min(position,Math.max(0,audio.duration-.1));},{once:true});}
  lastAudioTime=0;renderTrack();setStatus(source?'准备播放':'音频未找到，请重新导入');persist();
  if('mediaSession'in navigator){try{navigator.mediaSession.metadata=new MediaMetadata({title:track.title,artist:'听见六级',album:track.type==='demo'?'功能体验 · 非真题':'听力练习'});}catch{}}
}
function setStatus(text){$('player-status').textContent=text;}
function ready(){if(audio.readyState>=1)return Promise.resolve();return new Promise((resolve,reject)=>{const timeout=setTimeout(()=>{clean();reject(new Error('音频加载超时，请检查网络或重新导入本地音频。'));},15000);function clean(){clearTimeout(timeout);audio.removeEventListener('loadedmetadata',ok);audio.removeEventListener('error',fail);}function ok(){clean();resolve();}function fail(){clean();reject(new Error('音频无法打开，请检查格式或链接。'));}audio.addEventListener('loadedmetadata',ok);audio.addEventListener('error',fail);});}
async function play(){try{await audio.play();}catch(e){setStatus('未能播放');toast(e.name==='NotAllowedError'?'浏览器需要你点击播放按钮。':`播放失败：${e.message}`);}}
async function playSentence(index,kind='sentence'){
  const s=current.segments[index];if(!s)return;
  try{await ready();const b=segmentBounds(current.segments,index,audio.duration);if(!b){toast('这句还没有有效的录音时间，请先校准。');return false;}boundary={...b,index,kind};audio.currentTime=b.start;activeIndex=index;highlight();await play();return true;}catch(e){toast(e.message);return false;}
}
async function togglePlay(){if(!audio.paused){audio.pause();return;}if(boundary&&audio.currentTime>=boundary.end-.05)audio.currentTime=boundary.start;if(state.mode==='sentence'&&!boundary){await playSentence(Math.max(0,activeIndex));return;}await play();}
function highlight(){document.querySelectorAll('#transcript .sentence').forEach(el=>el.classList.toggle('active',+el.dataset.index===activeIndex));}
function updateProgress(){const seconds=state.stats[today()]||0;$('today-minutes').innerHTML=`${Math.floor(seconds/60)}<small> 分钟</small>`;$('daily-fill').style.width=`${Math.min(100,seconds/1200*100)}%`;if(state.view==='progress')renderProgressStats();}
function onTime(){
  const t=audio.currentTime;$('elapsed').textContent=formatTime(t);$('duration').textContent=formatTime(audio.duration);$('calibration-clock').textContent=formatTime(t);if(!Number.isNaN(audio.duration)&&audio.duration>0){$('seek').max=audio.duration;$('seek').value=t;}
  const delta=t-lastAudioTime;if(!audio.paused&&delta>0&&delta<3){state.stats[today()]=(state.stats[today()]||0)+delta/audio.playbackRate;}lastAudioTime=t;
  if(performance.now()-lastStatsWrite>5000){if(current)state.positions[current.id]=t;persist();updateProgress();lastStatsWrite=performance.now();}
  const index=current?.segments.findIndex((s,i)=>{const b=segmentBounds(current.segments,i,audio.duration);return b&&t>=b.start&&t<b.end;})??-1;
  if(index!==activeIndex){activeIndex=index;highlight();if(state.view==='practice'&&state.mode!=='blind'&&index>=0&&document.visibilityState==='visible'){$('transcript').querySelector(`.sentence[data-index="${index}"]`)?.scrollIntoView({block:'nearest',behavior:'smooth'});}}
}
let stoppingBoundary=false;
function enforceBoundary(){
  if(!boundary||audio.paused||stoppingBoundary||audio.currentTime<boundary.end-.025)return;
  stoppingBoundary=true;
  if(state.loop){audio.currentTime=boundary.start;lastAudioTime=audio.currentTime;stoppingBoundary=false;return;}
  const done=boundary;audio.pause();audio.currentTime=done.end;setStatus('这一句已听完');stoppingBoundary=false;
  if(done.kind==='random'&&$('random-auto').checked){boundary=null;setTimeout(()=>{if(!boundary&&state.view==='random')nextRandom();},450);}
}
function frame(){enforceBoundary();requestAnimationFrame(frame);}
function clearBoundary(){boundary=null;state.loop=false;$('loop').setAttribute('aria-pressed','false');}
function skip(seconds){clearBoundary();const end=Number.isFinite(audio.duration)?audio.duration:0;audio.currentTime=Math.min(end,Math.max(0,audio.currentTime+seconds));lastAudioTime=audio.currentTime;}
function isFavorite(id,index){return state.favorites.some(x=>x.trackId===id&&x.index===index);}
function toggleFavorite(index){const at=state.favorites.findIndex(x=>x.trackId===current.id&&x.index===index);if(at>=0)state.favorites.splice(at,1);else state.favorites.push({trackId:current.id,index});persist();renderTranscript();toast(at>=0?'已取消收藏':'已收藏');}
function randomPool(){const scope=$('random-scope').value;const included=scope==='current'?[current]:tracks;const items=[];for(const track of included)track.segments.forEach((s,i)=>{if(scope==='favorites'&&!isFavorite(track.id,i))return;const duration=track.id===current.id?audio.duration:track.duration;if(segmentBounds(track.segments,i,duration))items.push(`${track.id}:${i}`);});return items;}
function renderRandom(){if(!randomItem)return;const track=tracks.find(x=>x.id===randomItem.trackId),s=track?.segments[randomItem.index];if(!s)return;$('random-source').textContent=`${track.title} · 第${randomItem.index+1}句`;$('random-text').textContent=randomVisible?s.text:'';$('random-translation').textContent=randomVisible&&state.translation?(s.translation||'暂无配套译文'):'';}
async function nextRandom(){
  const key=randomChoice(randomPool(),randomItem?`${randomItem.trackId}:${randomItem.index}`:null);if(!key){toast('这个范围内没有已校准句子。请先导入原文并标记时间，或更换范围。');return;}
  const split=key.lastIndexOf(':'),id=key.slice(0,split),index=+key.slice(split+1);const track=tracks.find(x=>x.id===id);
  if(track.id!==current.id)await selectTrack(track,{resume:false});randomItem={trackId:id,index};randomVisible=false;renderRandom();await playSentence(index,'random');
}
function renderLibrary(){
  $('library-count').textContent=`${tracks.length} 份`;
  $('library-list').innerHTML=tracks.map(t=>`<article class="card library-card"><span class="badge ${t.type==='demo'?'':'neutral'}">${t.type==='demo'?'功能体验 · 非真题':t.type==='source'?'六级真题 · 在线原声':t.audioUrl?'在线音频':'本地音频'}</span><h3>${escape(t.title)}</h3><p>${t.segments.length?`${t.segments.length} 句原文 · ${t.segments.filter(s=>s.start!==null).length} 句有时间标记`:'原声可直接听 · 原文与译文待导入'}</p><div class="library-actions"><button class="button ${t.id===current.id?'light':''}" data-load="${escape(t.id)}">${t.id===current.id?'继续练习':'开始练习'} →</button>${t.type==='source'?`<a class="text-button" href="${escape(t.sourceUrl)}" target="_blank" rel="noopener noreferrer">资料来源 ↗</a>`:t.type==='demo'?'':`<button class="delete-track" data-delete="${escape(t.id)}">移除</button>`}</div></article>`).join('');
}
function renderProgressStats(){$('total-minutes').textContent=Math.floor(Object.values(state.stats).reduce((n,x)=>n+Number(x||0),0)/60);$('practice-days').textContent=Object.values(state.stats).filter(x=>x>=1).length;$('favorite-count').textContent=state.favorites.length;}
function renderProgress(){renderProgressStats();const valid=state.favorites.filter(f=>tracks.find(t=>t.id===f.trackId)?.segments[f.index]);$('favorites-list').innerHTML=valid.length?valid.map(f=>{const t=tracks.find(t=>t.id===f.trackId),s=t.segments[f.index];return `<div class="favorite-row"><div><strong>${escape(s.text)}</strong><p>${escape(t.title)} · 第${f.index+1}句</p></div><button class="button light" data-review="${escape(t.id)}:${f.index}">重听</button></div>`;}).join(''):'<p class="quiet-note">暂无收藏</p>';}
function openImport(edit=false){
  $('import-form').reset();pendingBackup=null;editTrackId=edit?current.id:null;
  $('import-heading').textContent=edit?'添加或更新原文':'导入听力资料';$('import-title').value=edit?current.title:'';$('import-text').value=edit&&current.segments.length?JSON.stringify(current.segments,null,2):'';
  $('audio-file-label').hidden=edit;$('url-details').hidden=edit;$('import-error').textContent='';$('import-dialog').showModal();
}
async function submitImport(event){
  event.preventDefault();$('import-error').textContent='';$('import-submit').disabled=true;
  try{
    const title=$('import-title').value.trim();if(!title)throw new Error('请填写材料名称。');
    const raw=$('import-text').value;const segments=raw.trim()?parseTranscript(raw):[];
    if(editTrackId){const t=tracks.find(x=>x.id===editTrackId);const updated={...t,title,segments};await saveTrack(updated);tracks[tracks.indexOf(t)]=updated;current=updated;activeIndex=-1;boundary=null;renderTrack();}
    else {
      let blob=$('import-audio').files[0]||null;let url=safeUrl($('import-url').value.trim());
      if(!blob&&pendingBackup?.audioData){
        if(!/^data:audio\/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=\s]+$/.test(pendingBackup.audioData))throw new Error('备份里的音频格式不正确。');
        const response=await fetch(pendingBackup.audioData);blob=await response.blob();
      }
      if(!blob&&pendingBackup?.audioUrl)url=safeUrl(pendingBackup.audioUrl);
      if(!blob&&!url)throw new Error('请选择配套音频文件，或填写在线音频链接。');
      if(blob&&blob.size>100*1024*1024)throw new Error('音频超过100MB，请先按篇章拆分。');
      const track={id:crypto.randomUUID(),title,type:'import',audioUrl:blob?'':url,segments,createdAt:Date.now()};
      await saveImportedTrack(track,blob);tracks.push(track);await selectTrack(track,{resume:false});
    }
    $('import-dialog').close();goto('practice');toast('资料已保存，可以开始练习了。');
  }catch(e){$('import-error').textContent=e?.name==='QuotaExceededError'?'设备空间不足，资料未保存，请释放空间后再试。':e.message||'导入失败，请检查资料。';}
  finally{$('import-submit').disabled=false;}
}
async function readCaption(){
  const file=$('import-caption').files[0];if(!file)return;
  if(file.size>150*1024*1024){$('import-error').textContent='备份文件超过150MB。';return;}
  try{const text=await file.text();pendingBackup=null;if(file.name.endsWith('.json')){const data=JSON.parse(text);if(data.title&&data.segments){if(!editTrackId){pendingBackup=data;$('import-title').value=data.title;}$('import-text').value=JSON.stringify(data.segments,null,2);}else $('import-text').value=text;}else $('import-text').value=text;$('import-error').textContent='';}
  catch(e){$('import-error').textContent='文件无法读取：'+e.message;}
}
async function exportTrack(){
  try{const copy={version:1,title:current.title,segments:current.segments,audioUrl:current.audioUrl||''};let blob=current.type==='demo'?await fetch('./demo.wav').then(r=>r.blob()):await storage('audio','get',current.id);if(blob)copy.audioData=await new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(reader.result);reader.onerror=()=>reject(reader.error);reader.readAsDataURL(blob);});
    const file=new Blob([JSON.stringify(copy)],{type:'application/json'});const u=URL.createObjectURL(file);const link=document.createElement('a');link.href=u;link.download=`${current.title.replace(/[\\/:*?"<>|]/g,'_')}-完整备份.json`;link.click();setTimeout(()=>URL.revokeObjectURL(u),30000);toast('已导出备份，可在另一设备的「导入资料」中选择。');
  }catch(e){toast('备份失败：'+e.message);}
}
function openCalibration(){if(!current.segments.length){toast('请先添加原文，再校准句子时间。');return;}boundary=null;$('calibration-error').textContent='';$('calibration-list').innerHTML=current.segments.map((s,i)=>`<div class="calibration-row"><p>${i+1}. ${escape(s.text)}</p><div class="calibration-inputs"><label>句首（秒）<input data-start="${i}" type="number" min="0" step="0.01" value="${s.start??''}"></label><label>句尾（可留空）<input data-end="${i}" type="number" min="0" step="0.01" value="${s.end??''}"></label><button class="button light" data-mark="${i}">此刻为句首</button><button class="text-button" data-cue="${i}">听这一句</button></div></div>`).join('');$('calibration-dialog').showModal();}
async function saveCalibration(){
  try{const segments=validateSegments(current.segments.map((s,i)=>({...s,start:$('calibration-list').querySelector(`[data-start="${i}"]`).value,end:$('calibration-list').querySelector(`[data-end="${i}"]`).value})));
    if(Number.isFinite(audio.duration)&&segments.some(s=>(s.start!==null&&s.start>=audio.duration)||(s.end!==null&&s.end>audio.duration+.1)))throw new Error('时间标记超过录音长度，请检查。');
    const updated={...current,segments};await saveTrack(updated);tracks[tracks.indexOf(current)]=updated;current=updated;boundary=null;renderTranscript();$('calibration-dialog').close();toast('时间标记已保存，现在可以随机听句子了。');
  }catch(e){$('calibration-error').textContent=e.message;}
}
async function deleteTrack(id){
  const track=tracks.find(t=>t.id===id);if(!track||track.type==='demo')return;
  if(!confirm(`移除「${track.title}」及当前设备中的音频？此操作无法撤销。`))return;
  try{const tx=db.transaction(['tracks','audio'],'readwrite');tx.objectStore('tracks').delete(id);tx.objectStore('audio').delete(id);await new Promise((resolve,reject)=>{tx.oncomplete=resolve;tx.onabort=tx.onerror=()=>reject(tx.error);});tracks=tracks.filter(t=>t.id!==id);state.favorites=state.favorites.filter(f=>f.trackId!==id);delete state.positions[id];if(current.id===id)await selectTrack(tracks[0],{resume:false});persist();renderLibrary();toast('已移除资料。');}catch(e){toast('移除失败：'+e.message);}
}
async function install(){if(installPrompt){await installPrompt.prompt();await installPrompt.userChoice;installPrompt=null;}else $('install-dialog').showModal();}
function setupEvents(){
  document.querySelectorAll('[data-view]').forEach(el=>el.onclick=()=>goto(el.dataset.view));document.querySelectorAll('[data-go]').forEach(el=>el.onclick=()=>goto(el.dataset.go));
  document.querySelectorAll('.close-dialog').forEach(el=>el.onclick=()=>el.closest('dialog').close());
  ['open-import','library-import'].forEach(id=>$(id).onclick=()=>openImport());$('add-transcript').onclick=()=>openImport(true);
  $('import-form').onsubmit=submitImport;$('import-caption').onchange=readCaption;
  ['mode-transcript','mode-blind','mode-sentence'].forEach(id=>$(id).onclick=()=>setMode(id.replace('mode-','')));$('reveal').onclick=()=>setMode('transcript');
  $('translation-toggle').onchange=e=>{state.translation=e.target.checked;persist();renderTranscript();renderRandom();};
  $('transcript').onclick=e=>{const sentence=e.target.closest('[data-sentence]'),favorite=e.target.closest('[data-favorite]');if(sentence){const i=+sentence.dataset.sentence;if(current.segments[i].start===null){activeIndex=i;highlight();toast('此句尚未校准时间，可在下方标记句首。');}else if(state.mode==='sentence'||state.loop)playSentence(i);else{boundary=null;audio.currentTime=current.segments[i].start;lastAudioTime=audio.currentTime;play();}}if(favorite)toggleFavorite(+favorite.dataset.favorite);};
  $('play').onclick=togglePlay;$('rewind').onclick=()=>skip(-5);$('forward').onclick=()=>skip(5);
  $('previous').onclick=()=>playSentence(Math.max(0,activeIndex-1));$('next').onclick=()=>playSentence(Math.min(current.segments.length-1,activeIndex+1));
  $('seek').oninput=e=>{clearBoundary();audio.currentTime=+e.target.value;lastAudioTime=audio.currentTime;};
  $('speed').onchange=e=>{state.speed=+e.target.value;audio.playbackRate=state.speed;audio.preservesPitch=true;persist();toast(`已切换到 ${state.speed} 倍速`);};
  $('loop').onclick=()=>{if(!state.loop){const b=boundary||segmentBounds(current.segments,Math.max(0,activeIndex),audio.duration);if(!b){toast('请先校准句子时间，再使用循环。');return;}boundary={...b,index:boundary?.index??Math.max(0,activeIndex),kind:boundary?.kind||'sentence'};if(audio.currentTime<boundary.start||audio.currentTime>=boundary.end)audio.currentTime=boundary.start;}state.loop=!state.loop;$('loop').setAttribute('aria-pressed',state.loop);toast(state.loop?'单句循环已开启':'单句循环已关闭');};
  $('random-next').onclick=nextRandom;$('random-reveal').onclick=()=>{randomVisible=!randomVisible;renderRandom();};$('random-scope').onchange=()=>{randomItem=null;boundary=null;audio.pause();$('random-text').textContent='范围已更换，点击「随机一句」开始。';$('random-translation').textContent='';};
  $('library-list').onclick=async e=>{const load=e.target.closest('[data-load]'),remove=e.target.closest('[data-delete]');if(load){await selectTrack(tracks.find(t=>t.id===load.dataset.load));setMode('transcript');goto('practice');}if(remove)deleteTrack(remove.dataset.delete);};
  $('favorites-list').onclick=async e=>{const target=e.target.closest('[data-review]');if(!target)return;const key=target.dataset.review,i=key.lastIndexOf(':');await selectTrack(tracks.find(t=>t.id===key.slice(0,i)));goto('practice');setMode('sentence');await playSentence(+key.slice(i+1));};
  $('open-calibration').onclick=openCalibration;$('save-calibration').onclick=saveCalibration;$('calibration-play').onclick=togglePlay;$('calibration-back').onclick=()=>skip(-5);
  $('calibration-list').onclick=e=>{const mark=e.target.closest('[data-mark]'),cue=e.target.closest('[data-cue]');if(mark){$('calibration-list').querySelector(`[data-start="${mark.dataset.mark}"]`).value=audio.currentTime.toFixed(2);$('calibration-list').querySelector(`[data-end="${mark.dataset.mark}"]`).value='';}if(cue){const value=parseTime($('calibration-list').querySelector(`[data-start="${cue.dataset.cue}"]`).value);if(value!==null){boundary=null;audio.currentTime=value;play();}}};
  $('export-track').onclick=exportTrack;['install','install-alt'].forEach(id=>$(id).onclick=install);$('storage-persist').onclick=async()=>{const kept=await navigator.storage?.persist?.();toast(kept?'浏览器已允许保留离线资料。':'浏览器尚未授予长期保存，请定期导出备份。');};
  audio.addEventListener('timeupdate',()=>{onTime();enforceBoundary();});audio.addEventListener('seeking',()=>{lastAudioTime=audio.currentTime;});
  audio.addEventListener('loadedmetadata',()=>{audio.playbackRate=state.speed;current.duration=audio.duration;$('duration').textContent=formatTime(audio.duration);$('seek').max=audio.duration;});
  audio.addEventListener('play',()=>{$('play').textContent='Ⅱ';$('play').setAttribute('aria-label','暂停');lastAudioTime=audio.currentTime;setStatus(state.loop?'单句循环中':'正在播放');});
  audio.addEventListener('pause',()=>{$('play').textContent='▶';$('play').setAttribute('aria-label','播放');setStatus('已暂停');onTime();if(current)state.positions[current.id]=audio.currentTime;persist();updateProgress();});
  audio.addEventListener('waiting',()=>setStatus('正在加载音频…'));audio.addEventListener('playing',()=>setStatus(state.loop?'单句循环中':'正在播放'));
  audio.addEventListener('error',()=>{setStatus('音频无法打开');toast('音频无法播放，请检查文件格式；在线音频可下载后本地导入。');});
  audio.addEventListener('ended',()=>{if(boundary&&state.loop){audio.currentTime=boundary.start;play();}else if(boundary?.kind==='random'&&$('random-auto').checked){boundary=null;setTimeout(nextRandom,450);}else setStatus('播放完毕');});
  window.addEventListener('pagehide',()=>{if(current)state.positions[current.id]=audio.currentTime;persist();});
  window.addEventListener('beforeinstallprompt',e=>{e.preventDefault();installPrompt=e;});
  document.addEventListener('keydown',e=>{if(e.code==='Space'&&!/INPUT|TEXTAREA|SELECT|BUTTON/.test(e.target.tagName)&&!document.querySelector('dialog[open]')){e.preventDefault();togglePlay();}});
  if('mediaSession'in navigator){for(const [action,handler] of Object.entries({play:()=>play(),pause:()=>audio.pause(),seekbackward:()=>skip(-5),seekforward:()=>skip(5),previoustrack:()=>playSentence(Math.max(0,activeIndex-1)),nexttrack:()=>playSentence(Math.min(current.segments.length-1,activeIndex+1))})){try{navigator.mediaSession.setActionHandler(action,handler);}catch{}}}
}
async function init(){
  try{db=await openDB();}catch{toast('本地存储不可用，暂时只能体验示例。请退出隐私模式后重试。');}
  const response=await fetch('./demo.json');if(!response.ok)throw new Error('示例资料无法加载。');const demo=await response.json();demo.id='demo';demo.type='demo';
  try{const stored=JSON.parse(localStorage.getItem('cet6-demo-segments'));if(stored)demo.segments=validateSegments(stored);}catch{}
  const sourceResponse=await fetch('./sources.json');const sourceTracks=sourceResponse.ok?await sourceResponse.json():[];
  const imported=db?await storage('tracks','getAll'):[];
  tracks=[...sourceTracks.map(t=>imported.find(x=>x.id===t.id)||t),demo,...imported.filter(t=>!sourceTracks.some(s=>s.id===t.id))];setupEvents();await selectTrack(tracks.find(t=>t.id===state.selected)||demo);updateProgress();goto('practice');requestAnimationFrame(frame);
  if('serviceWorker'in navigator){navigator.serviceWorker.register('./sw.js').catch(()=>toast('离线页面暂未就绪，联网时仍可使用。'));}
}
init().catch(e=>{setStatus('加载失败');toast(e.message);});
