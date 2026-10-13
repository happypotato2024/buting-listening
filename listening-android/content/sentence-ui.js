function migrateSentenceRecords(){
  if(localStorage.getItem('buting-sentence-revision')==='1')return;
  const firstSentence=(trackId,index)=>{const track=tracks.find(t=>t.id===trackId);if(!track?.sentenceAligned)return index;const mapped=track.segments.findIndex(s=>s.parentIndex===index);return mapped>=0?mapped:0;};
  state.favorites=state.favorites.flatMap(f=>{const track=tracks.find(t=>t.id===f.trackId);return track?.sentenceAligned?track.segments.flatMap((s,index)=>s.parentIndex===f.index?[{...f,index}]:[]):[f];});
  for(const id of Object.keys(readerPositions))readerPositions[id]=firstSentence(id,readerPositions[id]);
  if(learningContext)learningContext.readerIndex=firstSentence(learningContext.trackId,learningContext.readerIndex||0);
  try{
    localStorage.setItem('tingjian-reader',JSON.stringify(readerPositions));
    if(learningContext)localStorage.setItem('tingjian-learning',JSON.stringify(learningContext));
    const old=localStorage.getItem('tingjian-review-last');if(old){const i=old.lastIndexOf(':');localStorage.setItem('tingjian-review-last',old.slice(0,i)+':'+firstSentence(old.slice(0,i),Number(old.slice(i+1))));}
    persist();localStorage.setItem('buting-sentence-revision','1');
  }catch{toast('旧版收藏迁移未保存，请检查存储空间。');}
}
const originalSentenceMode=setMode;
setMode=function(mode){
  if(mode==='sentence'){
    audio.pause();clearBoundary();activeIndex=Number(readerPositions[current.id])||0;
    originalSentenceMode(mode);showReader();
    if(current.segments[activeIndex]?.start!==null){playSentence(activeIndex);}
  }else originalSentenceMode(mode);
};
const originalReaderMove=moveReader;
moveReader=function(delta){originalReaderMove(delta);if(state.mode==='sentence')playSentence(Number(readerPositions[current.id])||0);};
const previousSentenceApply=applyMode;
applyMode=function(){previousSentenceApply();$('open-calibration').hidden=!!current?.sentenceAligned;if(current?.sentenceAligned)$('mode-tip').textContent=state.mode==='sentence'?'点英文听这一句 · 可慢听与循环':state.mode==='blind'?'先听声音，听完再查看原文':'点击英文，从这一句继续听';};
function setupSentenceUI(){
  const scope=$('random-scope');
  scope.querySelector('[value="all"]').textContent='全部真题与材料';
  for(const track of tracks.filter(t=>t.sentenceAligned)){const option=document.createElement('option');option.value=track.id;option.textContent=track.title;scope.append(option);}
  $('random-replay').onclick=async()=>{if(!randomItem){await nextRandom();return;}const item={...randomItem},track=tracks.find(t=>t.id===item.trackId);if(current.id!==track.id)await selectTrack(track,{resume:false});randomItem=item;await playSentence(item.index,'random');};
}
const oldSentenceInit=init;
init=async function(){await oldSentenceInit();setupSentenceUI();document.body.dataset.sentenceReady='true';};
