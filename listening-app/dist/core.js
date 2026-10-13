export const formatTime = value => {
  const s = Number.isFinite(value) ? Math.max(0,Math.floor(value)) : 0;
  return `${Math.floor(s/60).toString().padStart(2,'0')}:${(s%60).toString().padStart(2,'0')}`;
};
export function parseTime(value) {
  if(typeof value==='number') return Number.isFinite(value)&&value>=0 ? value : null;
  if(typeof value!=='string'||!value.trim()) return null;
  if(/^\d+(?:\.\d+)?$/.test(value.trim())) return +value;
  const p=value.trim().replace(',','.').split(':');
  if(p.length<2||p.length>3||p.some(x=>!/^\d+(?:\.\d+)?$/.test(x))) return null;
  if(+p.at(-1)>=60||(p.length===3&&+p[1]>=60))return null;
  return p.reduce((n,x)=>n*60+ +x,0);
}
const hasChinese = text => /[\u3400-\u9fff]/.test(text);
function bilingual(lines) {
  if(lines.length===1&&lines[0].includes('||')) {
    const [text,...translation]=lines[0].split('||'); return {text:text.trim(),translation:translation.join('||').trim()};
  }
  return {text:lines.filter(x=>!hasChinese(x)).join(' ').trim(),translation:lines.filter(hasChinese).join(' ').trim()};
}
export function validateSegments(input) {
  if(!Array.isArray(input)||!input.length)throw new Error('原文里没有可用句子。');
  if(input.length>3000)throw new Error('一次最多导入3000句，请把资料分成几篇。');
  let previous = -1;
  return input.map((s,i)=>{
    if(!s||typeof s.text!=='string'||!s.text.trim())throw new Error(`第${i+1}句缺少英文原文。`);
    const start=parseTime(s.start),end=parseTime(s.end);
    if(s.start!==null&&s.start!==undefined&&s.start!==''&&start===null)throw new Error(`第${i+1}句开始时间无效。`);
    if(s.end!==null&&s.end!==undefined&&s.end!==''&&end===null)throw new Error(`第${i+1}句结束时间无效。`);
    if(end!==null&&(start===null||end<=start))throw new Error(`第${i+1}句结束时间必须晚于开始时间。`);
    if(start!==null&&start<=previous)throw new Error(`第${i+1}句时间必须晚于上一句，请检查时间顺序。`);
    if(start!==null)previous=start;
    return {text:s.text.trim().slice(0,12000),translation:String(s.translation||'').trim().slice(0,12000),start,end};
  });
}
export function parseTranscript(raw) {
  const content=raw.replace(/^\uFEFF/,'').trim();
  if(!content)return [];
  if(/^[\[{]/.test(content)&&!/^\[\d{1,3}:/.test(content)){
    try { const data=JSON.parse(content);return validateSegments(Array.isArray(data)?data:data.segments); }
    catch(e){throw new Error('JSON原文格式不正确：'+e.message);}
  }
  if(content.includes('-->')) {
    const blocks=content.replace(/\r/g,'').split(/\n\s*\n/);
    const result=[];
    for(const block of blocks){
      const lines=block.split('\n').map(x=>x.trim()).filter(Boolean);
      const at=lines.findIndex(x=>x.includes('-->'));if(at<0)continue;
      const [a,b]=lines[at].split('-->').map(x=>x.trim().split(/\s/)[0]);
      result.push({...bilingual(lines.slice(at+1)),start:parseTime(a),end:parseTime(b)});
    }
    return validateSegments(result);
  }
  const result=[];
  for(const line of content.replace(/\r/g,'').split('\n').filter(x=>x.trim())){
    const match=line.match(/^\[(\d{1,3}:\d{2}(?:\.\d+)?)\]\s*(.*)$/);
    if(match){
      const parts=bilingual([match[2]]),start=parseTime(match[1]);
      if(!parts.text&&parts.translation&&result.length&&result.at(-1).start===start)result.at(-1).translation=parts.translation;
      else result.push({...parts,start,end:null});
    }
    else if(!/^\[(?:ti|ar|al|by|offset):/.test(line)){
      const parts=bilingual([line]);
      if(!parts.text&&parts.translation&&result.length)result.at(-1).translation=[result.at(-1).translation,parts.translation].filter(Boolean).join(' ');
      else result.push({...parts,start:null,end:null});
    }
  }
  return validateSegments(result);
}
export function segmentBounds(segments,index,duration){
  const s=segments[index];if(!s||s.start===null||!Number.isFinite(s.start))return null;
  const next=segments.slice(index+1).find(x=>Number.isFinite(x.start)&&x.start>s.start)?.start;
  const end=s.end??next??(Number.isFinite(duration)&&duration>0?duration:null);
  if(end===null||end<=s.start||(Number.isFinite(duration)&&duration>0&&s.start>=duration))return null;
  return {start:s.start,end:Number.isFinite(duration)&&duration>0?Math.min(end,duration):end};
}
export function randomChoice(items,previous,random=Math.random){
  const pool=items.filter(x=>x!==previous);const list=pool.length?pool:items;
  return list.length?list[Math.floor(random()*list.length)]:null;
}
export function safeUrl(value){
  if(!value)return '';
  try{const u=new URL(value);if(u.protocol!=='https:')throw 0;return u.href;}catch{throw new Error('音频链接必须是完整的 HTTPS 地址。');}
}
