'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const html=fs.readFileSync('app/src/main/assets/index.html','utf8');
const start=html.indexOf('let zipReviewSession=null;');
const end=html.indexOf('async function processZip(){',start);
assert(start>=0&&end>start,'Large ZIP native preview handler not found');
const handler=html.slice(start,end);
assert(!handler.includes('function(raw){'),'ZIP callback parameter collides with local TXT raw');
const actualParser=html.slice(html.indexOf('function parseFestivalPostTxt(raw){'),html.indexOf('function matchFestivalPostTxt('));
const parse=Function(actualParser+'\nreturn parseFestivalPostTxt')();
let errors=[],notifications=[],deleted=[],displayed=0;
const context={
 console:{error:e=>errors.push(e)},
 window:{PostFlowNative:{deleteMedia:id=>deleted.push(id)}},
 document:{getElementById:id=>id==='zipMode'?{value:'draft'}:id==='zipInput'?{files:[]}:null},
 toast:msg=>notifications.push(msg),
 DEST:[['ig_story','Instagram Story']],
 state:{settings:{time:'09:00'},wpAccounts:[]},
 activeWorkspace:()=>null,
 accountKey:a=>a.platform+':'+a.external_id,
 safeFileName:p=>p.split('/').pop(),
 parseFestivalPostTxt:parse,
 zipCaptionTextMap:()=>({}),
 zipTextCaptionFor:()=>({caption:'',warning:''}),
 matchFestivalPostTxt:(path,parsed)=>({record:parsed?.byFilename.get(path)||null,warning:parsed&&!parsed.byFilename.has(path)?'TXT missing media':''}),
 csvRows:()=>({}),
 parseManifestDate:v=>v||'',
 guessDate:()=>'',titleFromName:path=>path.split('/').pop(),
 validFestivalDateTime:(date,time)=>/^\d{4}-\d{2}-\d{2}$/.test(date)&&/^\d\d:\d\d$/.test(time),
 pickCategory:()=> 'festival',
 makeCaption:()=> 'Generated fallback caption',
 showZipReview:()=>{displayed++;}
};
vm.createContext(context);
vm.runInContext(handler,context);
const fixture={
 ok:true,
 entries:[{path:'poster.jpg',mediaId:'z-test-media',size:13200,crc:1489,preview:'data:image/jpeg;base64,AQID'}],
 metadata:[{path:'captions.txt',text:'[POST]\nfilename=poster.jpg\ntitle=Festival Test\ntype=story\nplatform=instagram\ndate=2026-11-08\ntime=09:00\ncaption=Approved Story caption'}]
};
context.window.onPostFlowLargeZipResult(JSON.stringify(fixture));
assert.equal(displayed,1,'ZIP review did not appear');
assert.equal(errors.length,0,'Unexpected ZIP processing exception: '+errors.join(' / '));
assert(notifications.some(m=>m.includes('Large ZIP ready: 1 media items')),'Expected review success notification');
assert.equal(deleted.length,0,'Poster deleted before user confirmed');
let review=vm.runInContext('zipReviewSession',context);
assert.equal(review.rows.length,1);
assert.equal(review.rows[0].type,'story');
assert.equal(review.rows[0].date,'2026-11-08');
assert.equal(review.rows[0].caption,'Approved Story caption');
assert.equal(review.rows[0].canAutoSchedule,true);
assert.equal(review.rows[0].problems.length,0);
context.discardNativeZip=undefined;
// Simulate parse exception before native session ownership: all staged media must be cleaned.
vm.runInContext('nativeZipSession=null; zipReviewSession=null;',context);
context.parseFestivalPostTxt=()=>{throw Error('simulated malformed TXT')};
context.window.onPostFlowLargeZipResult(JSON.stringify(fixture));
assert(deleted.includes('z-test-media'),'Staged media leaked on parse failure');
assert(notifications.some(e=>String(e).includes('simulated malformed TXT')),'Error not reported');
console.log('Native >200MB ZIP callback: TXT parsing, Story preview, schedule metadata, ReferenceError guard and failure cleanup PASSED');
