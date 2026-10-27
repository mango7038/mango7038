'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const base='app/src/main/';
const activity=fs.readFileSync(base+'java/com/postflow/studio/MainActivity.java','utf8');
const importer=fs.readFileSync(base+'java/com/postflow/studio/LargeZipImporter.java','utf8');
const provider=fs.readFileSync(base+'java/com/postflow/studio/FestivalWidgetProvider.java','utf8');
const imageCache=fs.readFileSync(base+'java/com/postflow/studio/WidgetImageCache.java','utf8');
const html=fs.readFileSync(base+'assets/index.html','utf8');
const xml=fs.readFileSync(base+'res/layout/widget_festival.xml','utf8');
const script=html.slice(html.indexOf('<script>')+8,html.lastIndexOf('</script>'));
new Function(script);
for(const item of ['widgetCreate','widgetUpload','widgetPoster','widgetCalendar','widgetDate6','widgetPrevPost','widgetNextPost']){
 assert(xml.includes('@+id/'+item),'Widget control missing: '+item);
}
assert(xml.includes('widget_background'),'Glass widget background missing');
assert(provider.includes('int shown=7;'),'Widget must show seven days');
assert(provider.includes('private static boolean isActive'),'Missing active-status filter');
assert(provider.includes('return onDate(state(c),day);'),'Widget preview not using unified job dataset');
assert(provider.includes('for(Item item:onDate(root,day))'),'Dots and previews must use same active-job state');
assert(!provider.includes('inActiveWorkspace('),'Widget must include GN and SM accounts');
assert(!provider.includes('"published".equals(status)||"draft"'),'Cancelled or published/draft posts must not be active');
assert(provider.includes('boolean onServer=false;'),'Server cancellation must override stale local jobs');
assert(provider.includes('postAccount(root,p)'),'Account attribution required');
assert(provider.includes('WidgetImageCache.get(c,current.remoteUrl)'),'WordPress-only poster thumbnails required');
assert(imageCache.includes('"graphicanest.in".equalsIgnoreCase(uri.getHost())'),'Remote media host must be pinned');
assert(imageCache.includes('setInstanceFollowRedirects(false)'),'Remote thumbnail redirect protection missing');
assert(activity.includes('isLargeZip(picked.get(0))'),'Large ZIP must be detected before WebView buffers it');
assert(activity.includes('LargeZipImporter.extract(this,uri)'),'Native ZIP streaming not wired');
assert(importer.includes('new ZipInputStream(stream)'),'Native ZIP import must stream');
assert(importer.includes('MAX_MEDIA_COUNT=250'),'Media count guard missing');
assert(importer.includes('MAX_EXTRACTED_BYTES'),'Zip bomb guard missing');
assert(importer.includes('MAX_MEDIA_BYTES'),'Per-file limit missing');
assert(importer.includes('staged.add(id)'),'Staged source images must be addressable by mediaId');
for(const item of ['function openNativeLargeZip()','onPostFlowLargeZipResult','function discardNativeZip()','function importNativeZipAfterReview()','canAutoSchedule:!!rec','rec.canAutoSchedule','PostFlowNative?.deleteMedia']){
 assert(html.includes(item),'ZIP review/cleanup requirement missing: '+item);
}
assert(html.includes("if(z.nativeEntries)importNativeZipAfterReview()"),'Review must confirm before scheduling large ZIPs');
assert(html.includes('const ready=future&&!transformed.warning&&rec.canAutoSchedule;'),'Unverified ZIP rows must be drafted, never automatically published');
console.log('Widget visual controls, account-independent dots/previews, authoritative cancellation, native ZIP streaming, TXT-safe scheduling, JS syntax: PASS');
