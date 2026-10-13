let myPreferences=normalizeStudyPreferences(),myReviewHistory={},myTab='study';
try{myPreferences=normalizeStudyPreferences(JSON.parse(localStorage.getItem('tingjian-preferences')||'{}'));}catch{}
try{myReviewHistory=normalizeReviewHistory(JSON.parse(localStorage.getItem('tingjian-review-history')||'{}'));}catch{}
function recordReviewed(){
  const key=dayKey(),next={...myReviewHistory,[key]:(myReviewHistory[key]||0)+1};
  try{localStorage.setItem('tingjian-review-history',JSON.stringify(next));myReviewHistory=next;}catch{toast('复习统计未保存，请检查设备存储空间。');}
}
function saveStudyPreferences(patch){
  const next=normalizeStudyPreferences({...myPreferences,...patch});
  try{localStorage.setItem('tingjian-preferences',JSON.stringify(next));myPreferences=next;applyStudyPreferences();renderStudy();return true;}
  catch{toast('设置未保存，请检查设备存储空间。');syncStudyControls();return false;}
}
function applyStudyPreferences(){document.documentElement.style.setProperty('--reader-font',myPreferences.fontSize+'px');$('random-auto').checked=myPreferences.randomAuto;syncStudyControls();updateProgress();}
function syncStudyControls(){
  $('pref-speed').value=String(state.speed);$('pref-translation').checked=state.translation;$('pref-mode').value=myPreferences.mode;
  $('pref-autoplay').checked=myPreferences.autoPlay;$('pref-font').value=String(myPreferences.fontSize);$('pref-review-batch').value=String(myPreferences.reviewBatch);
  $('pref-review-translation').checked=myPreferences.reviewTranslation;$('pref-random-auto').checked=myPreferences.randomAuto;
}
function renderStudy(){
  const now=new Date(),metrics=studyMetrics(state.stats,myReviewHistory,dayKey(now)),summary=checkinSummary(checkinDays,now);
  $('my-today-minutes').textContent=metrics.todayMinutes;$('total-minutes').textContent=metrics.totalMinutes;$('practice-days').textContent=metrics.practiceDays;
  $('my-today-reviewed').textContent=metrics.todayReviewed;$('my-total-reviewed').textContent=metrics.totalReviewed;$('favorite-count').textContent=validReviewItems().length;
  $('my-goal-minutes').textContent=metrics.todayMinutes;$('my-goal-target').textContent=myPreferences.goalMinutes;
  const percentage=Math.min(100,metrics.todaySeconds/(myPreferences.goalMinutes*60)*100);$('my-goal-fill').style.width=percentage+'%';$('my-goal-progress').setAttribute('aria-valuenow',String(Math.round(percentage)));
  const material=tracks.find(track=>track.id===learningContext?.trackId)||current;$('my-plan-material').textContent=material?.title||'选择自己的精听材料';
  $('my-checkin-summary').textContent=`累计${summary.total}天 · 连续${summary.streak}天`;
  const monday=new Date(now.getFullYear(),now.getMonth(),now.getDate()-(now.getDay()+6)%7,12),signed=new Set(checkinDays);
  $('my-week-days').innerHTML=Array.from({length:7},(_,index)=>{const date=new Date(monday.getFullYear(),monday.getMonth(),monday.getDate()+index,12),key=dayKey(date),isToday=key===dayKey(now);return `<span class="${signed.has(key)?'signed':''} ${isToday?'today':''}" aria-label="${key}${signed.has(key)?' 已签到':' 未签到'}" ${isToday?'aria-current="date"':''}>${isToday?'今':date.getDate()}</span>`;}).join('');
  $('my-wallpaper-name').textContent=({winter:'雪林',dusk:'暮色',mist:'浅雾',photo:'本机图片'})[wallpaperState.kind]||'雪林';syncStudyControls();
}
function switchMyTab(tab){
  myTab=tab;['study','preferences'].forEach(value=>{const selected=value===tab,button=$(`my-${value}-tab`);$(`my-${value}`).hidden=!selected;button.setAttribute('aria-selected',String(selected));button.tabIndex=selected?0:-1;});window.scrollTo({top:0,behavior:'instant'});renderStudy();
}
const originalStudyGoto=goto;
goto=function(view){
  originalStudyGoto(view);
  if(view==='progress'){document.querySelector('.player').hidden=true;audio.pause();renderStudy();switchMyTab(myTab);}
};
const originalStudyStats=renderProgressStats;
renderProgressStats=function(){originalStudyStats();renderStudy();};
function setupStudy(){
  ['study','preferences'].forEach(tab=>{$(`my-${tab}-tab`).onclick=()=>switchMyTab(tab);$(`my-${tab}-tab`).onkeydown=event=>{if(['ArrowLeft','ArrowRight','Home','End'].includes(event.key)){event.preventDefault();const next=event.key==='Home'?'study':event.key==='End'?'preferences':myTab==='study'?'preferences':'study';switchMyTab(next);$(`my-${next}-tab`).focus();}};});
  $('my-plan-edit').onclick=()=>{$('plan-minutes').value=String(myPreferences.goalMinutes);$('plan-error').textContent='';$('plan-dialog').showModal();};
  $('plan-form').onsubmit=event=>{event.preventDefault();if(saveStudyPreferences({goalMinutes:Number($('plan-minutes').value)})){$('plan-dialog').close();toast('每日目标已保存。');}else $('plan-error').textContent='目标未保存，请检查设备存储空间。';};
  $('pref-speed').onchange=event=>{$('speed').value=event.target.value;$('speed').dispatchEvent(new Event('change'));};
  $('pref-translation').onchange=event=>{$('translation-toggle').checked=event.target.checked;$('translation-toggle').dispatchEvent(new Event('change'));};
  $('speed').addEventListener('change',syncStudyControls);$('translation-toggle').addEventListener('change',syncStudyControls);
  $('pref-mode').onchange=event=>saveStudyPreferences({mode:event.target.value});$('pref-autoplay').onchange=event=>saveStudyPreferences({autoPlay:event.target.checked});
  $('pref-font').onchange=event=>saveStudyPreferences({fontSize:Number(event.target.value)});$('pref-review-batch').onchange=event=>saveStudyPreferences({reviewBatch:event.target.value==='all'?'all':Number(event.target.value)});
  $('pref-review-translation').onchange=event=>saveStudyPreferences({reviewTranslation:event.target.checked});$('pref-random-auto').onchange=event=>saveStudyPreferences({randomAuto:event.target.checked});
  $('random-auto').addEventListener('change',event=>saveStudyPreferences({randomAuto:event.target.checked}));$('wallpaper-dialog').addEventListener('close',renderStudy);
  document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible'&&state.view==='progress')renderStudy();});
  applyStudyPreferences();renderStudy();document.body.dataset.studyReady='true';
}
const originalStudyInit=init;
init=async function(){await originalStudyInit();setupStudy();};
