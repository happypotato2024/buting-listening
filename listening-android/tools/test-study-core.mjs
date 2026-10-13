import assert from 'node:assert/strict';
import {normalizeStudyPreferences,normalizeReviewHistory,studyMetrics} from '../content/study-core.mjs';
assert.equal(normalizeStudyPreferences({goalMinutes:999,mode:'sentence',fontSize:10,reviewBatch:0}).goalMinutes,20);
assert.equal(normalizeStudyPreferences({autoPlay:false}).autoPlay,false);
assert.equal(normalizeStudyPreferences({reviewBatch:'all'}).reviewBatch,'all');
assert.deepEqual(normalizeReviewHistory({'2026-10-12':3.2,'bad':5,'2026-10-11':-2}),{'2026-10-12':3,'2026-10-11':0});
assert.deepEqual(studyMetrics({'2026-10-12':150,'2026-10-11':90,'bad':-10},{'2026-10-12':3,'2026-10-11':4},'2026-10-12'),{todayMinutes:2,totalMinutes:4,practiceDays:2,todayReviewed:3,totalReviewed:7,todaySeconds:150});
assert.equal(studyMetrics({today:NaN},{},'today').totalMinutes,0);
console.log('PASS preference migration defaults, invalid values, review counts and real-time statistics');
