let readerPositions={};
try{readerPositions=JSON.parse(localStorage.getItem('tingjian-reader')||'{}');}catch{}
function showReader(){
  if(!current)return;
  const total=current.segments.length;
  let index=Number(readerPositions[current.id])||0;
  index=Math.min(Math.max(0,index),Math.max(0,total-1));readerPositions[current.id]=index;
  document.querySelectorAll('#transcript .sentence').forEach((el,i)=>el.hidden=i!==index);
  $('reader-counter').textContent=total?`${String(index+1).padStart(2,'0')} / ${String(total).padStart(2,'0')}`:'原声音频';
  $('reader-previous').disabled=index<=0;$('reader-next').disabled=index>=total-1;
  $('reader-previous').textContent=current.unit==='句'?'上一句':'上一段';$('reader-next').textContent=current.unit==='句'?'下一句 →':'下一段 →';
  $('reader-nav').hidden=state.mode==='blind'||!total;
}
function moveReader(delta){
  if(!current?.segments.length)return;
  const index=Number(readerPositions[current.id])||0;
  readerPositions[current.id]=Math.min(Math.max(0,index+delta),current.segments.length-1);
  try{localStorage.setItem('tingjian-reader',JSON.stringify(readerPositions));}catch{}
  $('transcript').scrollTop=0;showReader();
}
const originalRenderTranscript=renderTranscript;
renderTranscript=function(){originalRenderTranscript();showReader();};
const originalApplyMode=applyMode;
applyMode=function(){originalApplyMode();showReader();};
const originalHighlight=highlight;
highlight=function(){originalHighlight();if(current&&activeIndex>=0&&current.segments[activeIndex]?.start!==null){readerPositions[current.id]=activeIndex;showReader();}};
function setupReader(){
  const now=new Date(),week=['日','一','二','三','四','五','六'];
  $('ui-date').textContent=`${now.getMonth()+1}月${now.getDate()}日 · 周${week[now.getDay()]}`;
  $('reader-previous').onclick=()=>moveReader(-1);$('reader-next').onclick=()=>moveReader(1);
  let touch;
  $('transcript').addEventListener('touchstart',event=>{touch=null;if(event.target.closest('.favorite-button,input'))return;const t=event.touches[0];touch={x:t.clientX,y:t.clientY};},{passive:true});
  $('transcript').addEventListener('touchend',event=>{if(!touch)return;const t=event.changedTouches[0],dx=t.clientX-touch.x,dy=t.clientY-touch.y;touch=null;if(Math.abs(dx)>65&&Math.abs(dy)<45)moveReader(dx<0?1:-1);},{passive:true});
}
