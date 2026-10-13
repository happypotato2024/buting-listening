let checkinDays=[];
try{checkinDays=validCheckins(JSON.parse(localStorage.getItem('tingjian-checkins')||'[]'));}catch{}
let calendarCursor=new Date(new Date().getFullYear(),new Date().getMonth(),1,12);
let homeDB,wallpaperURL=null,wallpaperState={kind:'winter',text:'dark'},wallpaperBusy=false;
let learningContext=null,homeActionBusy=false,reviewQueue=[],reviewIndex=0;
try{const value=JSON.parse(localStorage.getItem('tingjian-learning')||'null');if(value&&typeof value.trackId==='string'&&Number.isFinite(value.time)&&value.time>=0)learningContext=value;}catch{}
function rememberLearning(){
  if(!current)return;
  learningContext={trackId:current.id,time:audio.readyState>=1?audio.currentTime:(state.positions[current.id]||0),readerIndex:Number(readerPositions[current.id])||0};
  try{localStorage.setItem('tingjian-learning',JSON.stringify(learningContext));}catch{toast('精听进度未能保存，请检查设备存储空间。');}
}
function validReviewItems(){return state.favorites.filter(f=>tracks.find(t=>t.id===f.trackId)?.segments[f.index]);}
async function continueLearning(){
  if(homeActionBusy)return;homeActionBusy=true;$('home-learn').disabled=true;
  try{
    const track=tracks.find(t=>t.id===learningContext?.trackId)||current,position=learningContext?.trackId===track.id?learningContext.time:(state.positions[track.id]||0),readerIndex=learningContext?.trackId===track.id?learningContext.readerIndex:(readerPositions[track.id]||0);
    await selectTrack(track,{resume:false});readerPositions[track.id]=readerIndex;clearBoundary();setMode(myPreferences.mode);goto('practice');showReader();
    await ready();if(state.view!=='practice')return;
    audio.currentTime=Math.min(position,Math.max(0,audio.duration-.1));lastAudioTime=audio.currentTime;if(myPreferences.autoPlay)await play();
  }catch(error){toast(error.message||'精听音频未能打开，请重试。');}
  finally{homeActionBusy=false;$('home-learn').disabled=false;}
}
async function showReviewItem(){
  const item=reviewQueue[reviewIndex],track=tracks.find(t=>t.id===item?.trackId),segment=track?.segments[item?.index];if(!segment){goto('home');return;}
  const from=state.view;audio.pause();clearBoundary();await selectTrack(track,{resume:false});await ready();if(state.view!==from)return;audio.pause();
  $('review-position').textContent=`${reviewIndex+1} / ${reviewQueue.length}`;$('review-source').textContent=`${track.title} · 第${item.index+1}${track.unit||'句'}`;
  $('review-text').textContent=segment.text;$('review-translation').textContent=segment.translation||'暂无配套译文';$('review-translation').hidden=!myPreferences.reviewTranslation;
  $('review-reveal').textContent=myPreferences.reviewTranslation?'收起译文':'查看译文';$('review-reveal').setAttribute('aria-expanded',String(myPreferences.reviewTranslation));
  const timed=!!segmentBounds(track.segments,item.index,audio.duration);
  document.body.dataset.reviewTimed=String(timed);$('play').textContent='▶';$('play').setAttribute('aria-label','播放');onTime();
  $('review-play').textContent=timed?'听这一句':'播放整套原声';$('review-note').textContent=timed?'听这句原声，再核对译文。':'这份真题暂无逐段时间标记；可对照收藏原文听整套录音。';
  $('review-next').textContent=reviewIndex===reviewQueue.length-1?'完成本轮':'下一句 →';goto('review');
}
async function startReview(){
  if(homeActionBusy)return;const items=validReviewItems();if(!items.length){toast('先在原文旁点击 ☆，收藏想复习的句子。');return;}
  homeActionBusy=true;$('home-review').disabled=true;
  try{
    let last='';try{last=localStorage.getItem('tingjian-review-last')||'';}catch{}
    const start=(items.findIndex(item=>`${item.trackId}:${item.index}`===last)+1)%items.length;
    const ordered=[...items.slice(start),...items.slice(0,start)];reviewQueue=ordered.slice(0,myPreferences.reviewBatch==='all'?ordered.length:myPreferences.reviewBatch);reviewIndex=0;await showReviewItem();
  }catch(error){toast(error.message||'复习资料未能打开，请重试。');}
  finally{homeActionBusy=false;$('home-review').disabled=false;}
}
async function nextReview(){
  if(homeActionBusy)return;homeActionBusy=true;$('review-next').disabled=true;
  try{
    const item=reviewQueue[reviewIndex];if(!item)return;
    recordReviewed();
    try{localStorage.setItem('tingjian-review-last',`${item.trackId}:${item.index}`);}catch{toast('本轮位置未保存，请检查设备存储空间。');}
    audio.pause();clearBoundary();reviewIndex++;
    if(reviewIndex>=reviewQueue.length){goto('home');toast('本轮收藏复习完成。');}else await showReviewItem();
  }catch(error){toast(error.message||'下一段未能打开，请重试。');}
  finally{homeActionBusy=false;$('review-next').disabled=false;}
}
const wallpaperBackgrounds={winter:'url("./wallpaper-winter.svg")',dusk:'linear-gradient(155deg,#272f51 0%,#756884 45%,#e3b89d 75%,#f1d9c7 100%)',mist:'radial-gradient(ellipse at 25% 30%,#f6eadc 0,transparent 50%),linear-gradient(155deg,#d0dfe7,#edf1ed 60%,#cadfd5)'};
function renderHome(){
  const now=new Date(),summary=checkinSummary(checkinDays,now),week=['Sun.','Mon.','Tue.','Wed.','Thu.','Fri.','Sat.'];
  $('home-checkin-label').textContent=summary.signed?'已签到':'签到';$('home-checkin').classList.toggle('signed',summary.signed);
  $('home-checkin').setAttribute('aria-label',summary.signed?'今日已签到，查看签到记录':'今日签到，查看签到记录');
  $('home-checkin-date').textContent=`${now.getMonth()+1}/${now.getDate()} ${week[now.getDay()]}`;
  $('my-checkin-summary').textContent=`累计${summary.total}天 · 连续${summary.streak}天`;
}
function renderCalendar(){
  const now=new Date(),summary=checkinSummary(checkinDays,now),year=calendarCursor.getFullYear(),month=calendarCursor.getMonth(),signed=new Set(checkinDays);
  $('checkin-total').textContent=summary.total;$('checkin-streak').textContent=summary.streak;$('calendar-month').textContent=`${year}年${month+1}月`;
  $('calendar-next').disabled=year>now.getFullYear()||(year===now.getFullYear()&&month>=now.getMonth());
  $('checkin-calendar').innerHTML=calendarCells(year,month).map(cell=>cell?`<span role="listitem" data-day="${cell.key}" class="calendar-day ${signed.has(cell.key)?'checked':''} ${cell.key===dayKey(now)?'today':''}" aria-label="${cell.key}${signed.has(cell.key)?' 已签到':' 未签到'}" ${cell.key===dayKey(now)?'aria-current="date"':''}>${cell.day}</span>`:'<span class="calendar-pad" aria-hidden="true"></span>').join('');
  $('calendar-checkin').textContent=summary.signed?'今日已签到':'今日签到';$('calendar-checkin').disabled=summary.signed;
}
function signIn(){
  const key=dayKey();if(checkinDays.includes(key))return true;
  const next=validCheckins([...checkinDays,key]);
  try{localStorage.setItem('tingjian-checkins',JSON.stringify(next));checkinDays=next;renderHome();renderCalendar();toast('已签到');return true;}
  catch{toast('签到未保存，请检查设备存储空间后重试。');return false;}
}
function openCalendar(){const now=new Date();calendarCursor=new Date(now.getFullYear(),now.getMonth(),1,12);renderCalendar();$('checkin-dialog').showModal();}
function wallpaperStore(operation,value){return new Promise((resolve,reject)=>{
  if(!homeDB)return reject(new Error('本机存储不可用，请重启应用后再试。'));
  const tx=homeDB.transaction('settings',operation==='get'?'readonly':'readwrite'),store=tx.objectStore('settings'),req=operation==='get'?store.get('wallpaper'):store.put(value,'wallpaper');let result;
  req.onsuccess=()=>result=req.result;tx.oncomplete=()=>resolve(result);tx.onerror=()=>reject(tx.error||req.error);tx.onabort=()=>reject(tx.error||new Error('壁纸未保存。'));
});}
function applyWallpaper(){
  if(wallpaperURL){URL.revokeObjectURL(wallpaperURL);wallpaperURL=null;}
  if(wallpaperState.nativePhotoUrl)wallpaperURL=wallpaperState.nativePhotoUrl;
  else if(wallpaperState.photo instanceof Blob)wallpaperURL=URL.createObjectURL(wallpaperState.photo);
  const background=wallpaperState.kind==='photo'&&wallpaperURL?`url("${wallpaperURL}")`:wallpaperBackgrounds[wallpaperState.kind]||wallpaperBackgrounds.winter;
  $('home-backdrop').style.backgroundImage=background;$('wallpaper-preview').style.backgroundImage=background;
  document.body.dataset.homeText=wallpaperState.text;$('wallpaper-preview').dataset.text=wallpaperState.text;document.body.dataset.wallpaper=wallpaperState.kind;
  document.querySelectorAll('[data-wallpaper]').forEach(button=>{button.setAttribute('aria-pressed',String(button.dataset.wallpaper===wallpaperState.kind));if(button.dataset.wallpaper==='photo')button.disabled=!wallpaperURL;});
  document.querySelectorAll('[data-wallpaper-text]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.wallpaperText===wallpaperState.text)));
  $('photo-swatch').style.backgroundImage=wallpaperURL?`url("${wallpaperURL}")`:'';
}
function setWallpaperBusy(value){wallpaperBusy=value;$('wallpaper-dialog').setAttribute('aria-busy',String(value));$('wallpaper-file').disabled=value;$('wallpaper-pick').disabled=value;document.querySelectorAll('[data-wallpaper],[data-wallpaper-text],#wallpaper-reset').forEach(button=>button.disabled=value||(button.dataset.wallpaper==='photo'&&!wallpaperState.photo&&!wallpaperState.nativePhotoUrl));}
async function saveWallpaper(next){
  if(wallpaperBusy)return;setWallpaperBusy(true);
  try{
    if(globalThis.TingjianAndroid?.setWallpaper){if(!TingjianAndroid.setWallpaper(next.kind,next.text))throw new Error('本机壁纸未保存，请重试');wallpaperState=JSON.parse(TingjianAndroid.getWallpaper());}
    else{await wallpaperStore('put',next);wallpaperState=next;}
    applyWallpaper();$('wallpaper-status').textContent='已保存，重新打开应用也会保留。';
  }
  catch(error){$('wallpaper-status').textContent='保存失败：'+error.message;}
  finally{setWallpaperBusy(false);}
}
async function resizeWallpaper(file){
  if(file.type&&!file.type.startsWith('image/'))throw new Error('请选择图片文件。');
  if(file.size>30*1024*1024)throw new Error('图片超过30MB，请换一张较小的图片。');
  const url=URL.createObjectURL(file),image=new Image();
  try{
    image.src=url;await image.decode();
    if(!image.naturalWidth||!image.naturalHeight)throw new Error('图片无法读取。');
    const ratio=Math.min(1,1920/Math.max(image.naturalWidth,image.naturalHeight)),canvas=document.createElement('canvas');
    canvas.width=Math.max(1,Math.round(image.naturalWidth*ratio));canvas.height=Math.max(1,Math.round(image.naturalHeight*ratio));
    const context=canvas.getContext('2d');if(!context)throw new Error('图片处理暂不可用。');context.fillStyle='#d8e7ea';context.fillRect(0,0,canvas.width,canvas.height);context.drawImage(image,0,0,canvas.width,canvas.height);
    return await new Promise((resolve,reject)=>canvas.toBlob(blob=>blob?resolve(blob):reject(new Error('图片处理失败，请换一张重试。')),'image/jpeg',.88));
  }catch(error){if(error.name==='EncodingError')throw new Error('图片格式无法读取，请改用JPG、PNG或WebP图片。');throw error;}
  finally{URL.revokeObjectURL(url);}
}
async function chooseWallpaper(){
  const file=$('wallpaper-file').files[0];if(!file||wallpaperBusy)return;
  setWallpaperBusy(true);$('wallpaper-status').textContent='正在处理图片…';
  try{const photo=await resizeWallpaper(file),next={...wallpaperState,kind:'photo',photo};await wallpaperStore('put',next);wallpaperState=next;applyWallpaper();$('wallpaper-status').textContent='图片已设为首页壁纸，仅保存在本机。';}
  catch(error){$('wallpaper-status').textContent=error.message;}
  finally{$('wallpaper-file').value='';setWallpaperBusy(false);}
}
const originalGoto=goto;
goto=function(view){
  if(state.view==='practice'&&view!=='practice'&&document.body.dataset.homeReady==='true')rememberLearning();
  originalGoto(view);const home=view==='home';document.body.classList.toggle('home-view',home);document.body.dataset.view=view;
  document.querySelector('.player').hidden=home||view==='library';
  if(view==='library'||view==='random')audio.pause();if(home){audio.pause();renderHome();}if(view==='progress')renderHome();if(home)$('crumb').textContent='首页';if(view==='review')$('crumb').textContent='收藏复习';
};
async function setupHome(){
  applyWallpaper();
  try{
    homeDB=await new Promise((resolve,reject)=>{const request=indexedDB.open('tingjian-home',1);request.onupgradeneeded=()=>request.result.createObjectStore('settings');request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});
    const stored=await wallpaperStore('get');if(stored&&['winter','dusk','mist','photo'].includes(stored.kind)){wallpaperState={kind:stored.kind,text:stored.text==='light'?'light':'dark',...(stored.photo instanceof Blob?{photo:stored.photo}:{})};if(wallpaperState.kind==='photo'&&!wallpaperState.photo)wallpaperState.kind='winter';applyWallpaper();}
  }catch{$('wallpaper-status').textContent='本机存储不可用，暂时无法保存自定义壁纸。';}
  if(globalThis.TingjianAndroid?.getWallpaper){try{const native=JSON.parse(TingjianAndroid.getWallpaper());if(native){wallpaperState=native;applyWallpaper();}}catch{$('wallpaper-status').textContent='壁纸读取失败，请重新选择。';}}
  document.querySelector('.brand').onclick=event=>{event.preventDefault();goto('home');};
  $('home-learn').onclick=continueLearning;$('home-review').onclick=startReview;
  $('review-reveal').onclick=()=>{const shown=$('review-translation').hidden;$('review-translation').hidden=!shown;$('review-reveal').textContent=shown?'收起译文':'查看译文';$('review-reveal').setAttribute('aria-expanded',String(shown));};
  $('review-next').onclick=nextReview;
  $('review-play').onclick=async()=>{try{const item=reviewQueue[reviewIndex];if(!item)return;await ready();if(segmentBounds(current.segments,item.index,audio.duration))await playSentence(item.index);else{clearBoundary();await play();}}catch(error){toast(error.message||'录音未能打开，请重试。');}};
  audio.addEventListener('pause',()=>{if(state.view==='practice'&&document.body.dataset.homeReady==='true')rememberLearning();});
  window.addEventListener('pagehide',()=>{if(state.view==='practice')rememberLearning();});
  $('home-checkin').onclick=()=>{signIn();openCalendar();};$('calendar-checkin').onclick=signIn;$('my-checkins').onclick=openCalendar;
  $('calendar-previous').onclick=()=>{calendarCursor=new Date(calendarCursor.getFullYear(),calendarCursor.getMonth()-1,1,12);renderCalendar();};
  $('calendar-next').onclick=()=>{calendarCursor=new Date(calendarCursor.getFullYear(),calendarCursor.getMonth()+1,1,12);renderCalendar();};
  ['home-wallpaper','my-wallpaper'].forEach(id=>$(id).onclick=()=>{$('wallpaper-dialog').showModal();});
  $('wallpaper-pick').onclick=()=>{
    if(wallpaperBusy)return;
    if(globalThis.TingjianAndroid?.pickWallpaper){setWallpaperBusy(true);$('wallpaper-status').textContent='请选择相册中的图片…';try{TingjianAndroid.pickWallpaper();}catch{setWallpaperBusy(false);$('wallpaper-status').textContent='相册未能打开，请重试。';}}
    else $('wallpaper-file').click();
  };
  globalThis.butingWallpaperResult=result=>{
    if(result.status==='saved'&&result.wallpaper){wallpaperState=result.wallpaper;applyWallpaper();renderStudy();}
    $('wallpaper-status').textContent=result.message||'未更换壁纸。';setWallpaperBusy(false);
  };
  document.querySelectorAll('[data-wallpaper]').forEach(button=>button.onclick=()=>saveWallpaper({...wallpaperState,kind:button.dataset.wallpaper}));
  document.querySelectorAll('[data-wallpaper-text]').forEach(button=>button.onclick=()=>saveWallpaper({...wallpaperState,text:button.dataset.wallpaperText}));
  $('wallpaper-file').onchange=chooseWallpaper;$('wallpaper-reset').onclick=()=>saveWallpaper({kind:'winter',text:'dark'});
  document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible'){renderHome();if($('checkin-dialog').open)renderCalendar();}});
  setInterval(()=>{if(document.visibilityState==='visible'){renderHome();if(state.view==='progress')renderStudy();if($('checkin-dialog').open)renderCalendar();}},30000);
}
const originalInit=init;
init=async function(){await setupHome();await originalInit();if(!learningContext)rememberLearning();goto('home');document.body.dataset.homeReady='true';};
