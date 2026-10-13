function renderLibrary(){
  const year=$('library-year').value,query=$('library-search').value.trim().toLowerCase();
  const shown=tracks.filter(t=>{
    const trackYear=t.year||t.title.match(/20\d{2}/)?.[0];
    const matchYear=year==='all'||(year==='mine'?t.type==='import':String(trackYear)===year);
    return matchYear&&(!query||t.title.toLowerCase().includes(query));
  });
  $('library-count').textContent=`${shown.length} 份`;
  $('library-list').innerHTML=shown.length?shown.map(t=>`<article class="card library-card"><span class="badge neutral">${t.type==='demo'?'示例':t.audioAsset?'离线':t.audioUrl?'在线':'本地'}</span><h3>${escape(t.title)}</h3><p>${t.segments.length?`${t.segments.length} ${t.unit||'句'}`:'暂无原文'}${t.translationKind==='machine'?' · 机器译文':''}</p><div class="library-actions"><button class="button ${t.id===current.id?'light':''}" data-load="${escape(t.id)}">${t.id===current.id?'继续':'选择'}</button>${t.type==='import'?`<button class="delete-track" data-delete="${escape(t.id)}">移除</button>`:''}</div></article>`).join(''):'<p class="quiet-note">没有匹配的材料</p>';
}
