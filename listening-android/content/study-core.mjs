export const studyDefaults=Object.freeze({goalMinutes:20,mode:'transcript',autoPlay:true,fontSize:22,reviewBatch:10,reviewTranslation:false,randomAuto:false});
export function normalizeStudyPreferences(value={}){
  if(!value||typeof value!=='object')value={};
  return {goalMinutes:[5,10,20,30,45,60].includes(value.goalMinutes)?value.goalMinutes:20,mode:['transcript','blind'].includes(value.mode)?value.mode:'transcript',autoPlay:typeof value.autoPlay==='boolean'?value.autoPlay:true,fontSize:[20,22,26].includes(value.fontSize)?value.fontSize:22,reviewBatch:[5,10,20,'all'].includes(value.reviewBatch)?value.reviewBatch:10,reviewTranslation:value.reviewTranslation===true,randomAuto:value.randomAuto===true};
}
const nonnegative=value=>typeof value==='number'&&Number.isFinite(value)&&value>0?value:0;
export function normalizeReviewHistory(value){
  if(!value||typeof value!=='object'||Array.isArray(value))return {};
  return Object.fromEntries(Object.entries(value).filter(([key])=>/^\d{4}-\d{2}-\d{2}$/.test(key)).map(([key,count])=>[key,Math.floor(nonnegative(count))]));
}
export function studyMetrics(secondsByDay,reviewByDay,day){
  const seconds=secondsByDay&&typeof secondsByDay==='object'?secondsByDay:{},review=normalizeReviewHistory(reviewByDay);
  return {todayMinutes:Math.floor(nonnegative(seconds[day])/60),totalMinutes:Math.floor(Object.values(seconds).reduce((sum,value)=>sum+nonnegative(value),0)/60),practiceDays:Object.values(seconds).filter(value=>nonnegative(value)>0).length,todayReviewed:review[day]||0,totalReviewed:Object.values(review).reduce((sum,value)=>sum+value,0),todaySeconds:nonnegative(seconds[day])};
}
