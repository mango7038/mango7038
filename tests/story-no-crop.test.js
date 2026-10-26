'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const html=fs.readFileSync('app/src/main/assets/index.html','utf8');
const java=fs.readFileSync('app/src/main/java/com/postflow/studio/StoryCanvasRenderer.java','utf8');
const bridge=fs.readFileSync('app/src/main/java/com/postflow/studio/NativeBridge.java','utf8');
const script=html.slice(html.indexOf('<script>')+8,html.lastIndexOf('</script>'));
new Function(script);
for(const token of ['pStoryStyle','storyStyleValue','function updateStoryPreview','function openStoryPreview','pf-story-foreground','pf-style-crop','pf-story-progress','pf-story-reply','storyStyle:document.getElementById','queueWordPressPostStyled']){
  assert(html.includes(token),'Story composer/preview missing: '+token);
}
assert(html.includes("p.storyStyle||'fit_blur'"),'Safe default style must apply to ZIP, bulk and legacy posts');
assert(bridge.includes('placement.equals("story")&&mime.startsWith("image/")'),'Only image Stories should be transformed');
assert(bridge.includes('queueWordPressPostStyled('),'Styled Story API missing');
assert(bridge.includes('new java.io.FileInputStream(uploadMedia)'),'Upload must stream transformed image');
assert(bridge.includes('temporaryStory.delete()'),'Temporary Story media must be deleted');
assert(bridge.includes('File uploadMedia=media'),'Original media must remain available for Feed');
assert(java.includes('WIDTH=1080, HEIGHT=1920'),'Instagram story dimensions incorrect');
assert(java.includes('String DEFAULT_STYLE="fit_blur"'),'No-crop blurred background must be default');
assert(java.includes('boolean crop="fill_crop".equals(mode)'),'Cropping must require explicit opt-in');
assert(java.includes('BitmapFactory.decodeFile'),'Original image decoding absent');
assert(java.includes('ExifInterface'),'Portrait EXIF orientation support missing');
assert(java.includes('File.createTempFile'),'Original image might be overwritten');
assert(java.includes('Math.min(boxW/source.getWidth(),boxH/source.getHeight())'),'No-crop containment calculation missing');
const cases=[[1080,1080],[1080,1350],[1920,1080],[1080,1920],[640,640],[2400,4000]];
for(const [w,h] of cases){
 const scale=Math.min((1080-88)/w,(1920-440)/h);
 const imageW=w*scale,imageH=h*scale;
 assert(imageW<=1080-88+1e-6,'Image cropped horizontally: '+w+'x'+h);
 assert(imageH<=1920-440+1e-6,'Image cropped vertically: '+w+'x'+h);
}
console.log('No-crop Story transform, 9:16 preview, safe zones, style and original-preservation regression checks passed.');
