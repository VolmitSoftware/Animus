#!/usr/bin/env node
// Exact native codecs, viewer-owner fixtures, and actual managed Folia only.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { NativeClient, prepareNative, parseArgs, delay } from '../protocol-client/client.mjs';

const args=parseArgs();
const phase=args.get('phase')??'main';
assert(['main','bounds','shutdown'].includes(phase));
const harness=prepareNative(args,import.meta.url,`folia-${phase}`);
const {version,port,resultDir,nativeCodec,clients,wire}=harness;
const checks=[];
function pass(id,detail={}) { const check={id,status:'passed',...detail};checks.push(check);console.log(JSON.stringify({event:'check',...check})); }
function fields(text) { return Object.fromEntries([...text.matchAll(/([\w-]+)=([^ |]+)/g)].map(m=>[m[1],m[2]])); }
const near=(a,b)=>assert(Math.abs(a-b)<1e-5,`${a} != ${b}`);
class Client extends NativeClient {
  constructor(index) { super(harness,`AnimusFolia${index}`);this.index=index; }
  async setup(slot=this.index,world=null) {
    const reply=await this.command(`foliaqa setup ${slot}${world?` ${world}`:''}`,'Folia QA ready',25_000);
    const f=fields(reply.text);this.slot=slot;this.region=Number(f.region);
    assert.equal(f.owner,'true');assert.equal(f.rectangle,'true');assert.equal(f.sent,'true');
    this.feet={x:slot*8192+8.5,y:61,z:8.5};
    return f;
  }
  async status() { return (await this.command('animus foliaproof status','Folia stationary proof ready')).text; }
  async body(skin=null) {
    const from=this.events.length;
    const reply=await this.command(`animus foliaproof spawn${skin?` skin=${skin}`:''}`,'Folia proof spawned',20_000);
    const f=fields(reply.text),body={id:Number(f.id),uuid:f.uuid,generation:Number(f.generation),x:Number(f.x),y:Number(f.y),z:Number(f.z),height:Number(f.height)};
    assert.equal(f.preview,'true');assert.equal(f['owner-checked'],'true');
    const packet=await this.wait(e=>e.event==='spawn'&&e.id===body.id,from);
    assert.equal(packet.type,'minecraft:player');assert.equal(packet.uuid,body.uuid);
    for(const axis of ['x','y','z'])near(packet[axis],body[axis]);
    await this.command(`foliaqa expect ${body.uuid}`,`uuid=${body.uuid} world-registered=0 owner=true`);
    return body;
  }
  async clear(body) {
    const from=this.events.length;
    await this.command('animus foliaproof clear','Folia proof cleared');
    if(body)await this.removed(body,from);
  }
  async removed(body,from=0) {
    await this.wait(e=>e.event==='remove'&&e.ids.includes(body.id),from);
    await this.wait(e=>e.event==='player_info_remove'&&e.uuids.includes(body.uuid),from);
  }
  async look(body,away=false) {
    const from=this.events.length;
    this.send('look',{x:body.x,y:body.y+body.height/2,z:away?this.feet.z-4:body.z});
    await this.wait(e=>e.event==='look',from);
    // Owner command receipt is a server-side ordering barrier for the look packet.
    await this.command('foliaqa position','Folia QA position');
  }
  async hit(body,op='attack',expected='Folia proof accepted',extra={}) {
    await this.look(body);
    const from=this.events.length;this.send(op,{id:body.id,...extra});
    const reply=await this.wait(e=>e.event==='chat'&&e.text.includes(expected),from);
    return {reply,from};
  }
  async walk(z) {
    while(Math.abs(this.feet.z-z)>1e-6) {
      const next={...this.feet,z:this.feet.z+Math.sign(z-this.feet.z)*Math.min(.25,Math.abs(z-this.feet.z))};
      const from=this.events.length;this.send('move',next);
      await this.wait(e=>e.event==='movement_sent'&&Math.abs(e.z-next.z)<1e-6,from);this.feet=next;
    }
    const f=fields((await this.command('foliaqa position','Folia QA position')).text);
    for(const axis of ['x','y','z'])near(Number(f[axis]),this.feet[axis]);
  }
}
async function silent(client,body,op='attack',extra={}) {
  const from=client.events.length;client.send(op,{id:body.id,...extra});await delay(350);
  assert(!client.events.slice(from).some(e=>e.event==='chat'&&/Folia (proof accepted|QA event)/.test(e.text)),'Stale/foreign/off-ray input acquired authority');
}
function noWireBodies(client) {
  const known=new Set(),profiles=new Set(),bodies=new Set();
  for(const event of client.events) {
    if(event.event==='player_info'&&event.name==='AnimusFolia'){known.add(event.uuid);profiles.add(event.uuid);}
    if(event.event==='spawn'&&known.has(event.uuid))bodies.add(event.id);
    if(event.event==='remove')for(const id of event.ids)bodies.delete(id);
    if(event.event==='player_info_remove')for(const uuid of event.uuids)profiles.delete(uuid);
  }
  assert.equal(bodies.size,0,`${client.username}: live preview wire IDs after cleanup`);
  assert.equal(profiles.size,0,`${client.username}: live preview profiles after cleanup`);
}
let error;
try {
  const owner=new Client(0);const toolchain=await owner.ready();await owner.setup(0);
  pass('native-exact-codec',{version,protocol:toolchain.protocol});
  await owner.command('animus proof control status','Packet proof unavailable on Folia');pass('general-control-disabled-for-viewer');
  if(phase==='main') {
    const peer=new Client(1);await peer.ready();await peer.setup(1);
    assert.notEqual(owner.region,peer.region);
    pass('separate-region-ownership',{ownerRegion:owner.region,peerRegion:peer.region});
    let a=await owner.body(args.get('skin')),b=await peer.body();
    assert.notEqual(a.id,b.id);assert.notEqual(a.uuid,b.uuid);
    assert(!owner.events.some(e=>e.event==='spawn'&&e.uuid===b.uuid));assert(!peer.events.some(e=>e.event==='spawn'&&e.uuid===a.uuid));
    pass('private-wire-bodies',{owner:a,peer:b});
    if(args.get('skin')) {
      const json=JSON.parse(readFileSync(args.get('skin-file'),'utf8'));
      const digest=s=>createHash('sha256').update(s).digest('hex');
      const info=await owner.wait(e=>e.event==='player_info'&&e.uuid===a.uuid&&e.textures?.length);
      assert(info.textures.some(t=>t.signed&&t.valueSha256===digest(json.textureRaw)&&t.signatureSha256===digest(json.signature)));
      pass('signed-texture-payload',{uuid:a.uuid});
    }
    let hit=await owner.hit(a);
    await owner.wait(e=>e.event==='chat'&&e.text.includes(`Folia QA event uuid=${a.uuid} action=ATTACK preview=true owner=true world-registered=0`),hit.from);
    pass('owner-attack-event');
    hit=await owner.hit(a,'use','Folia proof accepted',{x:999999,y:-999999,z:999999});
    await owner.wait(e=>e.event==='chat'&&e.text.includes('action=INTERACT preview=true owner=true world-registered=0'),hit.from);
    pass('server-ray-ignores-forged-point');
    await owner.look(a,true);await silent(owner,a,'use',{x:0,y:1,z:0});pass('off-ray-forged-point-rejected');
    await owner.walk(5.5);await owner.hit(a,'attack','Folia proof rejected reason=OUT_OF_REACH');
    pass('walked-overreach-rejected');await owner.walk(8.5);
    await owner.command('foliaqa wall true','Folia QA wall=true');
    await owner.hit(a,'attack','Folia proof rejected reason=OCCLUDED');
    await owner.command('foliaqa wall false','Folia QA wall=false');pass('occluded-ray-rejected');
    await owner.command('foliaqa arm cancel','Folia QA armed=cancel');
    hit=await owner.hit(a,'attack','Folia proof interaction cancelled');
    assert(!owner.events.slice(hit.from).some(e=>e.event==='chat'&&e.text.includes('Folia proof accepted')));
    pass('cancellable-owner-event');
    await owner.look(a);const burst=owner.events.length,burstStarted=Date.now();owner.send('attack',{id:a.id,repeat:32});await delay(500);
    const accepted=owner.events.slice(burst).filter(e=>e.event==='chat'&&e.text.includes('Folia proof accepted')).length;
    const elapsed=Date.now()-burstStarted;assert(accepted<=Math.ceil(elapsed/100)+1,'Flood exceeded the 100ms interaction admission limit');pass('interaction-rate-bounded',{accepted,packets:32,elapsedMs:elapsed});
    await silent(peer,a);await silent(owner,b);pass('foreign-viewer-ids-inert');
    const h=fields((await owner.command('foliaqa health','Folia QA health=')).text);assert.equal(Number(h.health),20);pass('preview-does-not-damage-viewer');
    await owner.command('foliaqa arm clear','Folia QA armed=clear');
    hit=await owner.hit(a,'attack','Folia QA cleared during event');await owner.removed(a,hit.from);
    assert(!owner.events.slice(hit.from).some(e=>e.event==='chat'&&e.text.includes('Folia proof accepted')));
    pass('reentrant-clear-revokes-authority');await silent(owner,a);pass('retired-id-inert');
    a=await owner.body();await owner.command('foliaqa arm replace','Folia QA armed=replace');
    hit=await owner.hit(a,'attack','Folia QA replaced during event');await owner.removed(a,hit.from);
    const replacement=fields((await owner.wait(e=>e.event==='chat'&&e.text.includes('Folia proof spawned'),hit.from)).text);
    assert.notEqual(Number(replacement.id),a.id);assert(Number(replacement.generation)>a.generation);
    await silent(owner,a);await owner.clear({id:Number(replacement.id),uuid:replacement.uuid});pass('reentrant-replacement-isolated');
    const queued=owner.events.length;
    await owner.command(`foliaqa queueclear${args.get('skin')?` ${args.get('skin')}`:''}`,'Folia QA queued spawn then clear');
    await delay(350);assert(!(await owner.status()).includes('| body='));
    assert(!owner.events.slice(queued).some(e=>e.event==='chat'&&e.text.includes('Folia proof spawned')));
    pass('queued-spawn-clear-before-owner-admission');
    a=await owner.body();const tele=owner.events.length;
    await owner.command('foliaqa setup 2','Folia QA ready');owner.feet={x:16392.5,y:61,z:8.5};
    await owner.removed(a,tele);assert(!(await owner.status()).includes('| body='));await silent(owner,a);
    pass('cross-region-teleport-retirement');
    a=await owner.body();await owner.close();
    const reconnect=new Client(0);await reconnect.ready();await reconnect.setup(0);
    assert(!(await reconnect.status()).includes('| body='));await silent(reconnect,a);
    const fresh=await reconnect.body();assert.notEqual(fresh.id,a.id);assert(fresh.generation>a.generation);
    await reconnect.clear(fresh);pass('quit-reconnect-generation-isolation');
    let lifecycle=await reconnect.body(),lifecycleFrom=reconnect.events.length;
    await reconnect.setup(0,'world_nether');await reconnect.removed(lifecycle,lifecycleFrom);await silent(reconnect,lifecycle);
    assert(!(await reconnect.status()).includes('| body='));pass('world-change-retirement');
    lifecycle=await reconnect.body();lifecycleFrom=reconnect.events.length;
    await reconnect.command('foliaqa permission','Folia QA permission revoked');await reconnect.removed(lifecycle,lifecycleFrom);
    await reconnect.wait(e=>e.event==='chat'&&e.text.includes('Folia QA permission restored'),lifecycleFrom);
    assert(!(await reconnect.status()).includes('| body='));await silent(reconnect,lifecycle);pass('permission-revocation-retirement');
    lifecycle=await reconnect.body();lifecycleFrom=reconnect.events.length;
    await reconnect.command('foliaqa die');await reconnect.wait(e=>e.event==='health'&&e.health===0,lifecycleFrom);
    await reconnect.removed(lifecycle,lifecycleFrom);reconnect.send('respawn');
    await reconnect.wait(e=>e.event==='respawn',lifecycleFrom);await reconnect.wait(e=>e.event==='health'&&e.health>0,lifecycleFrom);
    await reconnect.setup(0,'world');assert(!(await reconnect.status()).includes('| body='));await silent(reconnect,lifecycle);pass('death-respawn-retirement');
    const pendingTeleport=reconnect.events.length;
    await reconnect.command('foliaqa queueteleport','Folia QA queued teleport completed success=true');
    await reconnect.wait(e=>e.event==='chat'&&e.text.includes('Folia proof request cancelled reason=viewer-teleported'),pendingTeleport);
    assert(!(await reconnect.status()).includes('| body='));
    assert(!reconnect.events.slice(pendingTeleport).some(e=>e.event==='chat'&&e.text.includes('Folia proof spawned')));
    pass('pending-spawn-teleport-before-owner-admission');await reconnect.setup(0);
    const pendingSame=reconnect.events.length;
    await reconnect.command('foliaqa queueteleport same','Folia QA queued teleport completed success=true');
    await reconnect.wait(e=>e.event==='chat'&&e.text.includes('Folia proof request cancelled reason=viewer-teleported'),pendingSame);
    assert(!(await reconnect.status()).includes('| body='));
    assert(!reconnect.events.slice(pendingSame).some(e=>e.event==='chat'&&e.text.includes('Folia proof spawned')));
    pass('pending-same-position-teleport-retirement');
    for(const mode of ['nearby','same']) {
      const visible=await reconnect.body(),from=reconnect.events.length;
      await reconnect.command(`foliaqa visibleteleport ${mode}`,'Folia QA visible teleport completed success=true');
      await reconnect.removed(visible,from);assert(!(await reconnect.status()).includes('| body='));await silent(reconnect,visible);
      pass(`visible-${mode}-teleport-retirement`);await reconnect.setup(0);
    }
    const reentrant=await reconnect.body();await reconnect.command('foliaqa arm teleportsame','Folia QA armed=teleportsame');
    const reentrantHit=await reconnect.hit(reentrant,'attack','Folia QA same-position event teleport');
    const epochs=fields(reentrantHit.reply.text);assert.notEqual(epochs['epoch-before'],epochs['epoch-after']);
    await reconnect.removed(reentrant,reentrantHit.from);
    assert(!reconnect.events.slice(reentrantHit.from).some(e=>e.event==='chat'&&e.text.includes('Folia proof accepted')));
    pass('reentrant-same-position-teleport-retirement',{before:epochs['epoch-before'],after:epochs['epoch-after']});await reconnect.setup(0);
    await reconnect.command('foliaqa hole true','Folia QA hole=true');
    const rejected=reconnect.events.length;
    await reconnect.command('animus foliaproof spawn','Target/support halo must be owned, loaded, sent, clear and on verified full-block ground');
    assert(!reconnect.events.slice(rejected).some(e=>e.event==='spawn'&&e.type==='minecraft:player'));
    await reconnect.command('foliaqa hole false','Folia QA hole=false');pass('unsupported-ground-refused-before-spawn');
    const probe=await reconnect.command('foliaqa probe','Folia QA terrain probe');
    const probeFields=fields(probe.text);assert.equal(probeFields.admitted,'false');assert.equal(probeFields['target-chunk-events'],'0');
    assert(Number(probeFields['total-chunk-events'])>0);
    assert.equal(probeFields['tickets-before'],'0,0,0,0,0,0,0,0,0');assert.equal(probeFields['tickets-after'],probeFields['tickets-before']);
    pass('unowned-footprint-refused-no-load',{receipt:probe.text,scope:'lower-level production terrain admission; zero load events and locked ticket-count snapshots; transient-ticket absence unverified'});
    const joining=new Client(9);await joining.ready();await joining.setup(0);
    await joining.command('foliaqa joinprobe','Folia QA next join probe armed');await joining.close();
    const admission=new Client(9);await admission.ready();
    const unsent=await admission.wait(e=>e.event==='chat'&&e.text.includes('Folia QA join footprint'));
    const unsentFields=fields(unsent.text);assert.equal(unsentFields.owner,'true');assert.equal(unsentFields.loaded,'true');assert.equal(unsentFields.sent,'false');
    await admission.wait(e=>e.event==='chat'&&e.text.includes('Target/support halo must be owned, loaded, sent, clear and on verified full-block ground'));
    assert(!admission.events.some(e=>e.event==='chat'&&e.text.includes('Folia proof spawned')));
    await admission.setup(0);const delivered=await admission.body();await admission.clear(delivered);
    pass('unavailable-footprint-refused-no-load',{receipt:unsent.text,scope:'actual command before target delivery, then genuine delivery succeeds'});
    const retirementStart=admission.events.length;admission.send('expect_disconnect');await admission.wait(e=>e.event==='disconnect_armed',retirementStart);
    admission.closing=true;admission.send('command',{text:'foliaqa queuequit'});
    await admission.wait(e=>e.event==='expected_disconnect'&&e.reason==='Folia QA retirement',retirementStart);
    await delay(250);
    const returned=new Client(9);await returned.ready();await returned.setup(0);
    assert(!(await returned.status()).includes('| body='));
    await returned.command('foliaqa transport','Folia QA transport handlers=0 event-loop-barrier=true');
    pass('scheduler-retirement-cleanup');await returned.command('foliaqa cleanup','Folia QA cleaned');
    await peer.clear(b);await peer.command('foliaqa cleanup','Folia QA cleaned');await reconnect.command('foliaqa cleanup','Folia QA cleaned');
    pass('explicit-clear-entity-and-profile-cleanup');
  } else if(phase==='bounds') {
    const peers=[owner];
    for(let i=1;i<9;i++){const p=new Client(i);await p.ready();await p.setup(0);peers.push(p);}
    const bodies=[];for(let i=0;i<8;i++)bodies.push(await peers[i].body());
    const from=peers[8].events.length;
    await peers[8].command('animus foliaproof spawn','Eight proof leases already admitted');
    assert(!peers[8].events.slice(from).some(e=>e.event==='chat'&&e.text.includes('Folia proof spawned')));
    assert((await owner.status()).includes('admitted=8/8'));pass('eight-viewers-ninth-refused',{admitted:8});
    for(let i=0;i<8;i++)await peers[i].clear(bodies[i]);
    await owner.command('foliaqa iohold','Folia QA file worker held');
    for(const p of peers.slice(0,2)) {
      p.send('command',{text:`animus foliaproof spawn skin=${args.get('skin')}`});
      const status=await p.status();assert(status.includes('phase=SKIN_PENDING'));
    }
    await peers[2].command(`animus foliaproof spawn skin=${args.get('skin')}`,'Two skin reads already pending');
    assert((await owner.status()).includes('skin-reads=2/2'));pass('concurrent-skin-read-bound');
    await owner.clear();await peers[1].clear();
    const afterSkin=owner.events.length;
    await owner.command('foliaqa iorelease','Folia QA file worker released');await delay(300);
    const released=await owner.status();assert(released.includes('skin-reads=0/2'));assert(!released.includes('| body='));
    assert(!owner.events.slice(afterSkin).some(e=>e.event==='chat'&&e.text.includes('Folia proof spawned')));
    pass('queued-skin-clear-before-completion');
    const commandStart=owner.events.length;
    await owner.command('foliaqa commandburst','Folia QA queued status callbacks=9');
    await owner.wait(e=>e.event==='chat'&&e.text.includes('admission queue is full'),commandStart);
    await delay(250);assert(owner.events.slice(commandStart).filter(e=>e.event==='chat'&&e.text.includes('Folia stationary proof ready')).length===8);
    pass('command-callback-bound');
    const stalled=owner.events.length;
    await owner.command('foliaqa stallclear','Folia QA watch stalled then cleared');
    await owner.command('foliaqa transport','Folia QA transport handlers=0 event-loop-barrier=true');
    assert(!owner.events.slice(stalled).some(e=>e.event==='chat'&&e.text.includes('Folia proof spawned')));
    pass('stalled-watch-retirement-handler-cleanup');
    for(const p of peers.slice(1))await p.close();
    const prior=Number((await owner.status()).match(/lifetime admissions=(\d+)\/64/)[1]);
    const burstReceipt=await owner.command('foliaqa retireburst','Folia QA retirement burst',20_000);
    assert.equal(Number(fields(burstReceipt.text).attempts),65-prior);
    assert(burstReceipt.text.includes('publication-stalled=true'));pass('slow-netty-retirement-bound',{receipt:burstReceipt.text});
    const limit=owner.events.length;
    await owner.command('animus foliaproof spawn','64 proof admissions exhausted');
    assert(!owner.events.slice(limit).some(e=>e.event==='chat'&&e.text.includes('Folia proof spawned')));
    const status=await owner.status();assert(status.includes('admitted=0/8'));assert(status.includes('lifetime admissions=64/64'));
    pass('lifetime-admission-ceiling',{admissions:64});
    await owner.command('foliaqa transport','Folia QA transport handlers=0 event-loop-barrier=true');pass('retired-transport-handlers-zero');
    await owner.command('foliaqa cleanup','Folia QA cleaned');
  } else {
    const peer=new Client(1);await peer.ready();await peer.setup(1);
    await owner.command('foliaqa iohold','Folia QA file worker held');
    peer.send('command',{text:`animus foliaproof spawn skin=${args.get('skin')}`});
    assert((await peer.status()).includes('phase=SKIN_PENDING'));
    const start=owner.events.length;
    const disabled=await owner.command('foliaqa pendingdisable','Folia QA plugin disable',15_000);
    const f=fields(disabled.text);assert.equal(f.enabled,'false');assert(Number(f.nanos)<1_000_000_000);
    assert(disabled.text.includes('admitted=2/8'));assert(disabled.text.includes('command-work=8/8'));assert(disabled.text.includes('skin-reads=1/2'));
    assert(disabled.text.includes('unavailable: closed | admitted=0/8'));
    pass('plugin-disable-pending-work-cleanup',{receipt:disabled.text,ownerBlockedUntilDisable:true,nettyStalledMs:3000});
    const held=await owner.command('foliaqa filestatus','Folia QA file status');
    for(const fragment of ['io-held=1','runningMutations=1','closed=true','quiescent=false'])assert(held.text.includes(fragment));
    // The engine deliberately leaves running trusted work cooperative. Release our
    // own hold only after actual plugin disable, then observe its real finally and
    // engine-owned storage cleanup instead of relying on an executor interruption.
    await owner.command('foliaqa iorelease','Folia QA file worker released');
    const deadline=Date.now()+5000;let drained;
    do {
      drained=await owner.command('foliaqa filestatus','Folia QA file status');
      if(drained.text.includes('quiescent=true'))break;
      await delay(100);
    }while(Date.now()<deadline);
    for(const fragment of ['io-held=0','admitted=0','runningMutations=0','queuedEngineNotifications=0','cleanupState=SUCCEEDED','quiescent=true'])assert(drained.text.includes(fragment));
    await owner.command('foliaqa transport','Folia QA transport handlers=0 event-loop-barrier=true');
    await peer.command('foliaqa transport','Folia QA transport handlers=0 event-loop-barrier=true');
    const closed=await owner.command('foliaqa closedstatus','Folia QA closed new-request-accepted=false');
    for(const fragment of ['admitted=0/8','pending-spawns=0/8','command-work=0/8','skin-reads=0/2'])assert(closed.text.includes(fragment));
    assert(!owner.events.slice(start).some(e=>e.event==='chat'&&e.text.includes('Folia proof spawned')));
    pass('disabled-late-completions-inert',{heldReceipt:held.text,drainedReceipt:drained.text,explicitReleaseAfterDisable:true});
  }
  for(const client of clients.filter(c=>!c.closing)) {
    await client.command('foliaqa transport','Folia QA transport handlers=0 event-loop-barrier=true');noWireBodies(client);
  }
  pass('no-live-wire-bodies-or-profiles-after-cleanup');
} catch(failure) { error=failure;console.error(failure.stack??String(failure)); }
finally { for(const client of clients)await client.close();await new Promise(resolve=>wire.end(resolve)); }
const result={event:'result',status:error?'failed':'passed',phase,version,port,scope:'viewer-private stationary preview',nativeCodec,checks,resultDir,error:error?.message};
writeFileSync(path.join(resultDir,'result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
if(error)process.exitCode=1;
