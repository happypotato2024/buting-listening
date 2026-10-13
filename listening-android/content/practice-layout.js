function nIcon(name){
 const paths={play:'<path d="m9 5 11 7-11 7Z" fill="currentColor" stroke="none"/>',pause:'<path d="M8 5v14M16 5v14" stroke-width="4"/>',retry:'<path d="M20 10a8 8 0 1 0-2 8M20 4v6h-6"/>',previous:'<path d="m15 6-6 6 6 6"/>',next:'<path d="m9 6 6 6-6 6"/>',repeat:'<path d="M20 7H8a5 5 0 0 0 0 10h2M4 17h12a5 5 0 0 0 0-10h-2M17 4l3 3-3 3M7 14l-3 3 3 3"/>',chevron:'<path d="m5 9 7 7 7-7"/>'};
 return `<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${paths[name]||paths.play}</svg>`;
}
function nPlayLabel(label){const b=$('n-toggle');if(!b||b.getAttribute('aria-label')===label)return;b.setAttribute('aria-label',label);b.title=label;b.innerHTML=nIcon(label==='暂停'?'pause':label==='重试'?'retry':'play');}
function nPracticeMarkup(view){return `
 <header class="native-bar native-heading"><h2>${view==='random'?'随听':view==='review'?'Review':'精听'}</h2>${nButton('n-library','更换资料')}${nButton('n-import','导入')}</header>
 <div class="native-status native-source" id="n-title"></div>
 <div class="native-modes"><div class="native-segment">${nButton('n-sentence-mode','单句')}${nButton('n-whole-mode','整篇')}</div>${nButton('n-preview','全文预览')}</div>
 <div class="native-practice-grid">
  <div class="native-reading">
   <div class="native-card"><div class="native-count"><span id="n-count"></span><button id="n-favorite" class="icon-button" aria-label="收藏句子">☆</button></div><div id="n-english" class="native-english"></div><div id="n-chinese" class="native-chinese"></div></div>
   <div class="native-reading-actions"><div class="native-navigation">${nButton('n-prev','上一句')}${nButton('n-repeat','重听')}${nButton('n-next',nReview?'下一条':'下一句')}</div><div class="native-reveal">${nButton('n-text','原文')}${nButton('n-translation','译文')}${nButton('n-random','随机一句')}</div></div>
  </div>
  <div class="native-playback-column">
   <div class="native-transport"><input id="n-seek" type="range" min="0" max="10000" value="0" aria-label="播放进度"><div class="native-time"><span id="n-elapsed">00:00</span><span id="n-duration">00:00</span></div>
    <div class="native-play-row"><select id="n-speed" aria-label="播放速度">${[.5,.65,.75,.85,1,1.25,1.5].map(v=>`<option value="${v}">${v}×</option>`).join('')}</select>${nButton('n-back','−5 秒')}<button id="n-toggle" class="native-play" aria-label="播放">${nIcon('play')}</button>${nButton('n-forward','+5 秒')}<button id="n-loop" class="button light" aria-label="单句循环" title="单句循环">${nIcon('repeat')}</button></div>
    <div class="native-status" id="n-status" role="status"></div>
   </div>
   <div class="native-utilities"><details id="n-process" class="native-details"><summary>导入进度</summary><div class="native-popover">${nButton('n-pause-process','暂停处理')}${nButton('n-resume','继续 / 重试译文')}${nButton('n-offline','离线翻译')}</div></details><details class="native-details"><summary>更多</summary><div class="native-popover">${nButton('n-stop','停止播放')}${nButton('n-edit','修正这句')}${nButton('n-feedback','标记问题')}${nButton('n-log','使用日志')}</div></details></div>
  </div>
 </div>`;}
