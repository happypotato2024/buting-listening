// One sentence request owns loading, seeking and playback. Navigation cancels it.
let sentenceRequest=null,randomRequest=0,randomAdvanceTimer=null,trackLoad=0,sentenceClip=null;
function audioPosition(){return audio.currentTime+(sentenceClip?.trackId===current?.id?sentenceClip.start:0);}
function cancelSentence(){
  sentenceRequest?.abort();sentenceRequest=null;
  clearTimeout(randomAdvanceTimer);randomAdvanceTimer=null;
}
function cancelRandom(){
  randomRequest++;trackLoad++;cancelSentence();audio.pause();boundary=null;
  $('random-next').disabled=false;$('random-replay').disabled=false;
  $('random-next').textContent='随机一句';
}
function mediaWait(test,events,signal,message){
  return new Promise((resolve,reject)=>{
    let timer;
    function cleanup(){clearTimeout(timer);events.forEach(e=>audio.removeEventListener(e,check));audio.removeEventListener('error',fail);signal?.removeEventListener('abort',abort);}
    function finish(error){cleanup();error?reject(error):resolve();}
    function check(){if(signal?.aborted)return abort();if(audio.error)return fail();if(test())finish();}
    function fail(){finish(new Error('音频加载失败，请重试。'));}
    function abort(){finish(new DOMException('Cancelled','AbortError'));}
    events.forEach(e=>audio.addEventListener(e,check));audio.addEventListener('error',fail);signal?.addEventListener('abort',abort,{once:true});
    timer=setTimeout(()=>finish(new Error(message)),15000);check();
  });
}
ready=function(signal){return mediaWait(()=>audio.readyState>=1,['loadedmetadata','durationchange'],signal,'音频加载超时，请重试。');};
selectTrack=async function(track,{resume=true}={}){
  if(current?.id===track.id&&!sentenceClip)return;
  const load=++trackLoad;cancelSentence();
  if(current){state.positions[current.id]=audioPosition();persist();}
  audio.pause();clearBoundary();activeIndex=-1;randomItem=null;
  let source=track.type==='demo'?'./demo.wav':track.audioAsset||track.audioUrl,blob;
  if(!source)blob=await storage('audio','get',track.id);
  if(load!==trackLoad)return;
  if(audioUrl){URL.revokeObjectURL(audioUrl);audioUrl=null;}
  if(blob){audioUrl=URL.createObjectURL(blob);source=audioUrl;}
  sentenceClip=null;current=track;state.selected=track.id;
  audio.src=source||'';audio.playbackRate=state.speed;audio.preservesPitch=true;audio.load();
  if(resume&&state.positions[track.id]>0){const position=state.positions[track.id];audio.addEventListener('loadedmetadata',()=>{if(load===trackLoad)audio.currentTime=Math.min(position,Math.max(0,audio.duration-.1));},{once:true});}
  lastAudioTime=0;renderTrack();setStatus(source?'已就绪':'音频未找到');persist();
  if('mediaSession'in navigator){try{navigator.mediaSession.metadata=new MediaMetadata({title:track.title,artist:'不听听力',album:'听力练习'});}catch{}}
};
playSentence=async function(index,kind='sentence'){
  cancelSentence();audio.pause();boundary=null;
  const request=new AbortController(),track=current,view=state.view;sentenceRequest=request;
  try{
    const segment=track?.segments[index];if(!segment)return false;
    if(view==='random'){
      kind='random';
      randomItem=Object.freeze({trackId:track.id,index,sentenceId:segment.sentenceId,text:segment.text,translation:segment.translation||'',audioClip:segment.audioClip});
      renderRandom();
    }
    setStatus('正在加载…');
    const clip=kind==='random'&&segment.audioClip;
    if(clip){
      sentenceClip={trackId:track.id,index,sentenceId:segment.sentenceId,start:segment.start,source:clip};
      if(audio.src!==new URL(clip,location.href).href){audio.src=clip;audio.load();}
    }else if(sentenceClip){
      sentenceClip=null;audio.src=track.audioAsset||track.audioUrl||audioUrl;audio.load();
    }
    // A failed source must be reloaded before retrying the same sentence.
    if(audio.error)audio.load();
    await ready(request.signal);
    const b=clip?{start:0,end:audio.duration}:segmentBounds(track.segments,index,audio.duration);
    if(!b)throw new Error('这句缺少时间标记，请换一句。');
    audio.currentTime=b.start;lastAudioTime=audioPosition();
    await mediaWait(()=>!audio.seeking&&audio.readyState>=2&&Math.abs(audio.currentTime-b.start)<.15,['seeked','loadeddata','canplay','timeupdate'],request.signal,'音频定位超时，请重试。');
    if(request.signal.aborted||current!==track||state.view!==view)return false;
    activeIndex=index;if(!clip)highlight();boundary={...b,index,kind};
    audio.playbackRate=state.speed;audio.preservesPitch=true;
    await audio.play();
    if(request.signal.aborted||current!==track||state.view!==view)return false;
    setStatus(state.loop?'单句循环中':'正在播放');return true;
  }catch(error){
    if(!request.signal.aborted&&error.name!=='AbortError'){
      boundary=null;audio.pause();setStatus('播放失败');toast(error.message||'播放失败，请重试。');
    }
    return false;
  }finally{if(sentenceRequest===request)sentenceRequest=null;}
};
// The player shows clip-relative time while stored learning positions remain
// relative to the complete recording. A clip must never overwrite its duration.
onTime=function(){
  const t=audio.currentTime,position=audioPosition();
  $('elapsed').textContent=formatTime(t);$('duration').textContent=formatTime(audio.duration);$('calibration-clock').textContent=formatTime(position);
  if(Number.isFinite(audio.duration)&&audio.duration>0){$('seek').max=audio.duration;$('seek').value=t;}
  const delta=position-lastAudioTime;
  if(!audio.paused&&delta>0&&delta<3)state.stats[today()]=(state.stats[today()]||0)+delta/audio.playbackRate;
  lastAudioTime=position;
  if(performance.now()-lastStatsWrite>5000){if(current)state.positions[current.id]=position;persist();updateProgress();lastStatsWrite=performance.now();}
  if(sentenceClip)return;
  const index=current?.segments.findIndex((s,i)=>{const b=segmentBounds(current.segments,i,audio.duration);return b&&t>=b.start&&t<b.end;})??-1;
  if(index!==activeIndex){activeIndex=index;highlight();}
};
renderRandom=function(){
  $('random-reveal').textContent=randomVisible?'收起原文':'查看原文';
  if(!randomItem)return;
  const track=tracks.find(t=>t.id===randomItem.trackId);
  $('random-source').textContent=`${track.title} · 第${randomItem.index+1}句`;
  $('random-text').textContent=randomVisible?randomItem.text:'';
  $('random-translation').textContent=randomVisible&&state.translation?randomItem.translation:'';
  $('random-text').dataset.sentenceId=randomItem.sentenceId||String(randomItem.index);
};
randomPool=function(){
  const scope=$('random-scope').value,chosen=tracks.find(t=>t.id===scope);
  const included=scope==='current'?[current]:chosen?[chosen]:tracks,items=[];
  for(const track of included)track.segments.forEach((s,index)=>{
    if(scope==='favorites'&&!isFavorite(track.id,index))return;
    const duration=track.id===current.id&&!sentenceClip?audio.duration:track.duration;
    if(segmentBounds(track.segments,index,duration))items.push(`${track.id}:${index}`);
  });
  return items;
};
function scheduleRandomAdvance(){
  clearTimeout(randomAdvanceTimer);const request=randomRequest;
  randomAdvanceTimer=setTimeout(()=>{
    randomAdvanceTimer=null;
    if(request===randomRequest&&state.view==='random'&&$('random-auto').checked&&!boundary&&!sentenceRequest)nextRandom();
  },450);
}
enforceBoundary=function(){
  if(!boundary||audio.paused||audio.seeking||sentenceRequest||stoppingBoundary||audio.currentTime<boundary.end-.025)return;
  stoppingBoundary=true;const done=boundary;
  if(state.loop){playSentence(done.index,done.kind);}
  else{audio.pause();audio.currentTime=done.end;lastAudioTime=done.end;setStatus('已听完');if(done.kind==='random'&&$('random-auto').checked){boundary=null;scheduleRandomAdvance();}}
  stoppingBoundary=false;
};
async function playRandomItem(item){
  cancelRandom();const request=randomRequest,track=tracks.find(t=>t.id===item.trackId);
  $('random-next').disabled=true;$('random-replay').disabled=true;$('random-next').textContent='加载中…';
  try{
    if(current?.id!==track.id)await selectTrack(track,{resume:false});
    if(request!==randomRequest||state.view!=='random')return;
    const segment=track.segments[item.index];
    randomItem=Object.freeze({...item,sentenceId:segment.sentenceId,text:segment.text,translation:segment.translation||'',audioClip:segment.audioClip});renderRandom();
    const ok=await playSentence(item.index,'random');
    if(request===randomRequest&&!ok){$('random-note').textContent='未能播放，请点「再听一遍」重试。';$('random-note').hidden=false;}
  }catch(error){if(request===randomRequest){setStatus('播放失败');toast(error.message||'播放失败，请重试。');}}
  finally{if(request===randomRequest){$('random-next').disabled=false;$('random-replay').disabled=false;$('random-next').textContent='随机一句';}}
}
nextRandom=async function(){
  const key=randomChoice(randomPool(),randomItem?`${randomItem.trackId}:${randomItem.index}`:null);
  if(!key){cancelRandom();toast('没有可播放的句子，请更换范围。');return;}
  const split=key.lastIndexOf(':');randomVisible=false;$('random-note').hidden=true;
  await playRandomItem({trackId:key.slice(0,split),index:+key.slice(split+1)});
};
const playbackGoto=goto;
goto=function(view){if(view!==state.view){cancelRandom();clearBoundary();}playbackGoto(view);};
const playbackToggle=togglePlay;
togglePlay=async function(){
  if(sentenceRequest){cancelRandom();setStatus('已暂停');return;}
  if(state.view==='random'&&audio.paused){if(randomItem)await playRandomItem({...randomItem});else await nextRandom();return;}
  await playbackToggle();
};
const playbackInit=init;
init=async function(){
  await playbackInit();
  $('play').onclick=togglePlay;
  $('random-next').onclick=nextRandom;
  $('random-replay').onclick=async()=>{if(!randomItem)return nextRandom();$('random-note').hidden=true;await playRandomItem({...randomItem});};
  $('random-scope').onchange=()=>{cancelRandom();clearBoundary();randomItem=null;randomVisible=false;$('random-source').textContent='';$('random-text').textContent='';$('random-translation').textContent='';$('random-reveal').textContent='查看原文';$('random-note').hidden=true;};
  $('random-auto').addEventListener('change',()=>{if(!$('random-auto').checked){clearTimeout(randomAdvanceTimer);randomAdvanceTimer=null;}});
  const fullLoop=$('loop').onclick;
  $('loop').onclick=()=>{
    if(!sentenceClip){fullLoop();return;}
    if(!Number.isFinite(audio.duration)||audio.duration<=0)return;
    state.loop=!state.loop;boundary={start:0,end:audio.duration,index:sentenceClip.index,kind:'random'};
    $('loop').setAttribute('aria-pressed',String(state.loop));
  };
  document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden')cancelRandom();});
  window.addEventListener('pagehide',cancelRandom);
  globalThis.butingPause=()=>{cancelRandom();};
};
