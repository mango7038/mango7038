"""Headless mobile Chromium UI/ZIP workflow tests. Native Android code is not executed here."""
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT=Path(__file__).resolve().parents[1]
HTML=(ROOT/'app/src/main/assets/index.html').read_text(encoding='utf-8')
JS=HTML[HTML.index('<script>')+8:HTML.rindex('</script>')]
SHELL=HTML[:HTML.index('<script src=')]+'</body></html>'
ZIP=ROOT/'sample/sample_dated_festivals.zip'

with sync_playwright() as pw:
    browser=pw.chromium.launch(headless=True,executable_path='/usr/bin/chromium',args=['--no-sandbox'])
    page=browser.new_page(viewport={'width':390,'height':844})
    errors=[]
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.set_content(SHELL)
    page.add_script_tag(content=(ROOT/'app/src/main/assets/jszip.min.js').read_text(encoding='utf-8'))
    page.evaluate('''window.__media={};window.PostFlowNative={
      getState:()=>window.__state||'{}',
      syncState:(s)=>{window.__state=s},
      beginMedia:(id,name)=>{window.__media[id]=[];return true},
      appendMedia:(id,part)=>{window.__media[id].push(part);return true},
      finishMedia:(id,ok)=>{if(!ok)delete window.__media[id]},
      hasMedia:(id)=>!!window.__media[id],
      deleteMedia:(id)=>delete window.__media[id],
      shareMedia:()=>{},shareText:()=>{},exportBackup:()=>{},importBackup:()=>{}
    };''')
    page.add_script_tag(content=JS)
    assert page.locator('h2').first.inner_text()=='Good to see you ✨'
    page.get_by_role('button',name='Import ZIP').first.click()
    page.locator('#zipInput').set_input_files(str(ZIP))
    page.get_by_role('button',name='Import and Process ZIP').click()
    page.wait_for_function('state.posts.length===3')
    assert page.evaluate('state.posts.filter(p=>p.status==="scheduled").length')==2
    assert page.evaluate('state.posts.filter(p=>p.category==="birthday"&&p.status==="draft").length')==1
    assert page.evaluate('Object.keys(window.__media).length')==3
    page.evaluate("route('home')")
    page.get_by_role('button',name='＋ Create Post').first.click()
    page.locator('#pTitle').fill('Only Story and WhatsApp')
    page.locator('#pDate').fill('2026-12-20')
    page.locator('#tar_ig_feed').uncheck()
    page.locator('#tar_ig_story').check()
    page.locator('#tar_wa_reminder').check()
    page.locator('#pStatus').select_option('scheduled')
    page.get_by_role('button',name='Save Post').click()
    page.wait_for_function('state.posts.length===4')
    assert page.evaluate('state.posts[3].targets.sort().join(",")')=='ig_story,wa_reminder'
    page.evaluate("route('events')")
    page.get_by_role('button',name='Add Fixed Indian Days').click()
    assert page.evaluate('state.events.length')>=14
    page.get_by_role('button',name='＋ Add Event').click()
    page.locator('#evTitle').fill('Private Client Birthday')
    page.locator('#evDate').fill('2026-12-12')
    page.locator('#evType').select_option('birthday')
    page.locator('#evRepeat').check()
    page.get_by_role('button',name='Save Event').click()
    assert page.evaluate('state.events.some(e=>e.title==="Private Client Birthday"&&e.repeat&&e.private)')
    page.evaluate("route('settings')")
    page.locator('#prefLanguage').select_option('mr')
    page.locator('#prefTime').fill('09:00')
    page.get_by_role('button',name='Save Preferences').click()
    assert page.evaluate('state.settings.captionLanguage')=='mr'
    assert page.evaluate('state.settings.time')=='09:00'
    assert not errors,errors
    print('PASS: ZIP extraction, original media streaming, auto schedule, private birthday draft, per-post Story+WhatsApp, events, caption settings, no JS crashes.')
    browser.close()