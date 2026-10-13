export function dayKey(date=new Date()){
  return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`;
}
export function offsetDay(date,delta){return new Date(date.getFullYear(),date.getMonth(),date.getDate()+delta,12);}
export function validCheckins(value){
  if(!Array.isArray(value))return [];
  return [...new Set(value.filter(key=>{if(typeof key!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(key))return false;const [y,m,d]=key.split('-').map(Number);return dayKey(new Date(y,m-1,d,12))===key;}))].sort();
}
export function checkinSummary(days,now=new Date()){
  const set=new Set(validCheckins(days));let cursor=set.has(dayKey(now))?now:offsetDay(now,-1),streak=0;
  while(set.has(dayKey(cursor))){streak++;cursor=offsetDay(cursor,-1);}
  return {total:set.size,streak,signed:set.has(dayKey(now))};
}
export function calendarCells(year,month){
  const first=new Date(year,month,1,12),padding=(first.getDay()+6)%7,count=new Date(year,month+1,0,12).getDate();
  return [...Array(padding).fill(null),...Array.from({length:count},(_,i)=>({day:i+1,key:dayKey(new Date(year,month,i+1,12))}))];
}
