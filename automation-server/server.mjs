import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';

const PORT=Number(process.env.PORT||8787), API_KEY=process.env.POSTFLOW_AUTOMATION_API_KEY||'';
const APP_SECRET=process.env.META_APP_SECRET||'',VERIFY_TOKEN=process.env.META_WEBHOOK_VERIFY_TOKEN||'';
const GRAPH_VERSION=process.env.META_GRAPH_VERSION||'v23.0';
const DATA_FILE=process.env.POSTFLOW_AUTOMATION_DATA_FILE||'./automation-data.json';
const TOKENS=(()=>{try{return JSON.parse(process.env.META_INSTAGRAM_ACCOUNTS_JSON||'{}')}catch{return {}}})();
if(!API_KEY||!APP_SECRET||!VERIFY_TOKEN)throw Error('Set POSTFLOW_AUTOMATION_API_KEY, META_APP_SECRET and META_WEBHOOK_VERIFY_TOKEN');
let db={rules:[],events:[],deliveries:[]},pending=Promise.resolve();
try{db={...db,...JSON.parse(await fs.readFile(DATA_FILE,'utf8'))}}catch(e){if(e.code!=='ENOENT')throw e}
const json=(res,code,obj)=>{res.writeHead(code,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(obj))};
const persist=()=>{pending=pending.then(async()=>{const temp=DATA_FILE+'.tmp';await fs.writeFile(temp,JSON.stringify(db));await fs.rename(temp,DATA_FILE)});return pending};
const validKey=req=>{const value=req.headers.authorization||'';const a=Buffer.from(value),b=Buffer.from('Bearer '+API_KEY);return a.length===b.length&&crypto.timingSafeEqual(a,b)};
const getBody=async req=>{let chunks=[],length=0;for await(const c of req){length+=c.length;if(length>1048576)throw Error('Payload too large');chunks.push(c)}return Buffer.concat(chunks)};
const normalizeRule=r=>({id:String(r.id||crypto.randomUUID()),name:String(r.name||'').slice(0,80),accountKey:String(r.accountKey||''),mediaId:String(r.mediaId||''),matchMode:r.matchMode==='any'?'any':'keywords',keywords:Array.isArray(r.keywords)?r.keywords.map(k=>String(k).trim().toUpperCase()).filter(Boolean).slice(0,20):[],publicEnabled:!!r.publicEnabled,publicText:String(r.publicText||'').slice(0,1000),dmEnabled:!!r.dmEnabled,dmText:String(r.dmText||'').slice(0,1000),enabled:!!r.enabled,updatedAt:new Date().toISOString()});
const ruleValid=r=>r.name&&r.accountKey.startsWith('instagram:')&&(r.matchMode==='any'||r.keywords.length)&&(!r.publicEnabled||r.publicText)&&(!r.dmEnabled||r.dmText)&&(r.publicEnabled||r.dmEnabled)&&(!r.mediaId||/^\d{5,100}$/.test(r.mediaId));
const findAccount=id=>Object.entries(TOKENS).find(([key,v])=>key==='instagram:'+id||String(v.igUserId)===String(id));
const graph=async(path,body,token)=>{const response=await fetch('https://graph.instagram.com/'+GRAPH_VERSION+'/'+path,{method:'POST',headers:{authorization:'Bearer '+token,'content-type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(12000)});const data=await response.json().catch(()=>({}));if(!response.ok||data.error)throw Error('Meta API '+response.status+': '+String(data.error?.message||'Unknown error').slice(0,250));return data};
const extract=payload=>{const result=[];for(const entry of payload.entry||[]){for(const c of entry.changes||[]){if(c.field!=='comments')continue;const v=c.value||{};if(v.id&&v.text&&v.media?.id)result.push({id:String(v.id),text:String(v.text),mediaId:String(v.media.id),accountId:String(entry.id||''),at:Date.now()})}}return result};
const matches=(r,c)=>r.enabled&&r.accountKey==='instagram:'+c.accountId&&(!r.mediaId||r.mediaId===c.mediaId)&&(r.matchMode==='any'||r.keywords.some(k=>c.text.toUpperCase().includes(k)));
let working=false;
async function processQueue(){
 if(working)return;working=true;
 try{for(const ev of db.events.filter(e=>e.status==='pending'||(e.status==='retry'&&e.nextAt<=Date.now()))){
  const r=db.rules.find(x=>x.id===ev.ruleId),account=findAccount(ev.accountId);
  if(!r||!matches(r,ev)||!account){ev.status='skipped';await persist();continue}
  const token=account[1].accessToken,igUserId=account[1].igUserId;
  if(!token||!igUserId){ev.status='blocked';ev.error='Missing server-side Instagram account credentials';await persist();continue}
  ev.status='processing';await persist();
  let dmOk=!r.dmEnabled,publicOk=!r.publicEnabled;
  try{
   if(r.dmEnabled&&!ev.dmSent){
    if(Date.now()-ev.at>7*86400000)throw Error('Private reply eligibility window expired');
    const d=await graph(igUserId+'/messages',{recipient:{comment_id:ev.id},message:{text:r.dmText}},token);
    ev.dmSent=true;ev.dmResponseId=d.message_id||d.id||'';dmOk=true;await persist();
   }else if(ev.dmSent)dmOk=true;
   if(r.publicEnabled&&!ev.publicSent){
    const message=dmOk?r.publicText:r.publicText.replace(/DM.{0,40}(sent|bhej|check)/ig,'details');
    const d=await graph(ev.id+'/replies',{message},token);
    ev.publicSent=true;ev.publicResponseId=d.id||'';publicOk=true;await persist();
   }else if(ev.publicSent)publicOk=true;
   ev.status=dmOk&&publicOk?'sent':'partial';ev.error='';
  }catch(error){ev.attempts=(ev.attempts||0)+1;ev.error=String(error.message).slice(0,350);ev.status=ev.attempts>=3?'failed':'retry';ev.nextAt=Date.now()+Math.min(60000*2**ev.attempts,900000)}
  db.deliveries.push({commentId:ev.id,ruleId:ev.ruleId,status:ev.status,dmSent:!!ev.dmSent,publicSent:!!ev.publicSent,error:ev.error||'',at:new Date().toISOString()});db.deliveries=db.deliveries.slice(-2000);await persist();
 }}finally{working=false}
}
const server=http.createServer(async(req,res)=>{
 try{
 const url=new URL(req.url,'http://localhost');
 if(url.pathname==='/health'&&req.method==='GET')return json(res,200,{ok:true,configuredAccounts:Object.keys(TOKENS).length});
 if(url.pathname==='/webhook'&&req.method==='GET')return url.searchParams.get('hub.mode')==='subscribe'&&url.searchParams.get('hub.verify_token')===VERIFY_TOKEN?(res.writeHead(200,{'content-type':'text/plain'}),res.end(url.searchParams.get('hub.challenge')||'')):json(res,403,{error:'Verification failed'});
 if(url.pathname==='/webhook'&&req.method==='POST'){
  const body=await getBody(req),sig=req.headers['x-hub-signature-256']||'';
  const expected='sha256='+crypto.createHmac('sha256',APP_SECRET).update(body).digest('hex');
  if(Buffer.byteLength(sig)!==Buffer.byteLength(expected)||!crypto.timingSafeEqual(Buffer.from(sig),Buffer.from(expected)))return json(res,403,{error:'Invalid signature'});
  const events=extract(JSON.parse(body.toString()));for(const c of events)for(const r of db.rules.filter(r=>matches(r,c))){if(!db.events.some(e=>e.id===c.id&&e.ruleId===r.id))db.events.push({...c,ruleId:r.id,status:'pending',attempts:0})}
  await persist();res.writeHead(200);res.end('EVENT_RECEIVED');void processQueue();return;
 }
 if(!validKey(req))return json(res,401,{error:'Unauthorized'});
 if(url.pathname==='/api/rules'&&req.method==='GET')return json(res,200,{rules:db.rules});
 if(url.pathname==='/api/rules'&&req.method==='PUT'){
  const input=JSON.parse((await getBody(req)).toString()),rules=Array.isArray(input.rules)?input.rules:[];
  if(rules.length>200)return json(res,400,{error:'Too many rules'});
  const normalized=rules.map(normalizeRule);
  if(normalized.some(r=>!ruleValid(r)))return json(res,400,{error:'Invalid rule configuration'});
  db.rules=normalized;await persist();return json(res,200,{ok:true,count:normalized.length});
 }
 if(url.pathname==='/api/analytics'&&req.method==='GET'){
  const d=db.deliveries;return json(res,200,{total:d.length,sent:d.filter(x=>x.status==='sent').length,failed:d.filter(x=>x.status==='failed').length,recent:d.slice(-50).reverse(),queued:db.events.filter(x=>['pending','retry','processing'].includes(x.status)).length});
 }
 return json(res,404,{error:'Not found'});
 }catch(e){console.error(e);return json(res,500,{error:'Request failed'})}
});
server.listen(PORT,()=>console.log('PostFlow automation server listening on '+PORT));
setInterval(()=>void processQueue(),30000).unref();
