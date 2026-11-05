package art.arcane.animus.bukkit.folia;

import art.arcane.animus.core.presentation.BodyPose;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import art.arcane.animus.bukkit.packet.NativePacketAdapter;
import art.arcane.animus.bukkit.packet.NativeViewerEpoch;
import art.arcane.animus.core.storage.*;
import art.arcane.animus.core.virtual.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/** Experimental viewer-private stationary proof. Does not enable the general Folia runtime. */
public final class FoliaStationaryProofService implements Listener, AutoCloseable {
    private static final int MAX_VIEWERS=8, MAX_ADMISSIONS=64;
    private static final long LIFETIME=120_000_000_000L;
    private final JavaPlugin plugin;
    private final String version, unavailable;
    private final DocumentStore documents;
    private final Executor io;
    private final FoliaRegions owner;
    private final NativeViewerEpoch epochs;
    private final StationaryTerrain terrain;
    private final ConcurrentMap<UUID, Lease> sessions=new ConcurrentHashMap<>();
    private final Semaphore viewers=new Semaphore(MAX_VIEWERS), reads=new Semaphore(2);
    private final PendingRequests<Object> commandWork=new PendingRequests<>(8);
    private final PendingRequests<Player> pendingSpawns=new PendingRequests<>(8);
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicInteger admissions=new AtomicInteger();
    private final AtomicLong retirements=new AtomicLong();

    public FoliaStationaryProofService(JavaPlugin plugin, String version, boolean enabled, DocumentStore documents, Executor io) {
        this.plugin=Objects.requireNonNull(plugin); this.version=version;
        this.documents=Objects.requireNonNull(documents); this.io=Objects.requireNonNull(io);
        FoliaRegions bound=null; NativeViewerEpoch epochBinding=null; String reason=null;
        if (!enabled) reason="disabled; packetProofEnabled must be true in config.toml";
        else if (!Set.of("26.1.2","26.2","26.3").contains(version)) reason="no exact adapter for "+version;
        else try { bound=new FoliaRegions(plugin); epochBinding=NativeViewerEpoch.bind(plugin,version); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { bound=null; reason="Folia ownership/epoch API unavailable: "+failure.getClass().getSimpleName(); }
        owner=bound; epochs=epochBinding; unavailable=reason; terrain=bound==null?null:new StationaryTerrain(bound);
        if (owner!=null) plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    public boolean available() { return owner!=null && !closed.get(); }
    /** Safe from console/global owner: never reads an entity or world. */
    public String status() {
        return "Folia stationary proof "+(available()?"ready; uncertified preview":"unavailable: "+(closed.get()?"closed":unavailable))
                +" | admitted="+sessions.size()+"/8 | lifetime admissions="+admissions.get()+"/64"
                +" | pending-spawns="+pendingSpawns.size()+"/8 | command-work="+commandWork.size()+"/8"
                +" | skin-reads="+(2-reads.availablePermits())+"/2 | retired="+retirements.get()
                +" | one PLAYER per viewer | separate from the Folia NPC runtime";
    }
    /** Bounded admission; entity reads require the owner at capture and in every scheduled callback. */
    public boolean execute(Player viewer, String operation, String skinHash) {
        String requested=operation.toLowerCase(Locale.ROOT);
        // Clear revokes immediately, even when callback admission is full. Its delayed callback only acknowledges.
        if(requested.equals("clear")) retire(viewer);
        if (!available()) return false;
        // Folia's same-region teleportAsync fast path can bypass PlayerTeleportEvent.
        // Capture the native connection/teleport epoch while owned, before scheduling anything.
        if(requested.equals("spawn") && !owner.owns(viewer))return false;
        NativeViewerEpoch.Stamp epoch=requested.equals("spawn")?epochs.capture(viewer):null;
        PendingRequests.Ticket<Object> callback=commandWork.begin(new Object());
        if(callback==null)return false;
        PendingRequests.Ticket<Player> ticket=requested.equals("spawn")?pendingSpawns.begin(viewer,epoch):null;
        if(requested.equals("spawn") && ticket==null){commandWork.finish(callback);return false;}
        Runnable released=()->{
            pendingSpawns.finish(ticket);
            commandWork.finish(callback);
        };
        return owner.execute(viewer,()->{
            try {
                if (!available() || ticket!=null && !pendingSpawns.current(ticket)) return;
                owner.require(viewer);
                if(ticket!=null && !pendingSpawns.current(ticket,epochs.capture(viewer))){
                    viewer.sendMessage("Folia proof request cancelled reason=viewer-teleported");return;
                }
                if (!viewer.isOnline() || viewer.isDead() || !viewer.hasPermission("animus.admin")) return;
                switch(requested) {
                    case "spawn" -> spawn(viewer,skinHash,ticket,epoch);
                    case "clear" -> viewer.sendMessage("Folia proof cleared");
                    case "status" -> {
                        Lease lease=sessions.get(viewer.getUniqueId());
                        if(lease!=null && lease.viewer!=viewer)lease=null;
                        viewer.sendMessage(status()+ (lease==null?"":" | body="+(lease.body==null?"pending":lease.body.entityId())
                                +" | uuid="+(lease.body==null?"pending":lease.body.uuid())+" | generation="+lease.generation+" | viewer-epoch="+lease.epoch.sequence()+" | phase="+lease.phase
                                +" | accepted="+lease.accepted+" | rejected="+lease.rejected+" | transport-ready="+lease.visible));
                    }
                    default -> throw new IllegalArgumentException("foliaproof status | spawn [skin-hash] | clear");
                }
            } catch (RuntimeException failure) { if(owner.owns(viewer)) viewer.sendMessage("Folia proof: "+failure.getMessage()); }
            finally { released.run(); }
        },released);
    }
    private void spawn(Player viewer, String skinHash, PendingRequests.Ticket<Player> ticket, NativeViewerEpoch.Stamp epoch) {
        owner.require(viewer);
        if(!pendingSpawns.current(ticket,epochs.capture(viewer)))return;
        if (skinHash!=null && !skinHash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Skin reference must be a SHA-256 hash");
        UUID id=viewer.getUniqueId();
        if (sessions.containsKey(id)) throw new IllegalStateException("Clear the current stationary proof before spawning another");
        if (!viewers.tryAcquire()) throw new IllegalStateException("Eight proof leases already admitted");
        Lease lease=null;
        try {
            Location origin=viewer.getLocation(); Vector facing=origin.getDirection().setY(0);
            if (facing.lengthSquared()<1e-6) facing=new Vector(0,0,1);
            Location desired=origin.clone().add(facing.normalize().multiply(3));
            Vec3 feet=new Vec3(Math.floor(desired.getX())+.5,Math.floor(desired.getY()),Math.floor(desired.getZ())+.5);
            World world=origin.getWorld();
            if (!terrain.supported(viewer,world,feet,.6,1.8)) throw new IllegalStateException("Target/support halo must be owned, loaded, sent, clear and on verified full-block ground");
            int number=admissions.getAndUpdate(value->value<MAX_ADMISSIONS?value+1:value);
            if(number>=MAX_ADMISSIONS)throw new IllegalStateException("64 proof admissions exhausted; restart required (bounds pending native retirements)");
            lease=new Lease(viewer,world,feet,origin.getYaw()+180,ticket.generation(),epoch);
            if(sessions.putIfAbsent(id,lease)!=null) throw new IllegalStateException("Viewer already admitted");
            Lease admitted=lease;
            // Invalidation may have scanned before this insertion; recheck the ticket after publishing the lease.
            if(closed.get() || !pendingSpawns.current(ticket,epochs.capture(viewer))) { admitted.resources.close(); return; }
            admitted.resources.task(owner.repeat(viewer,()->poll(admitted),admitted.resources::close));
            if (!current(admitted)) return;
            if (skinHash==null) beginTransport(admitted,null);
            else {
                if(!reads.tryAcquire()) throw new IllegalStateException("Two skin reads already pending");
                admitted.phase="SKIN_PENDING";
                SkinJob job=new SkinJob(admitted,skinHash); admitted.skinJob.set(job);
                if(!current(admitted)) {job.cancelQueued();return;}
                try {io.execute(job);} catch(RuntimeException failure) {job.cancelQueued();throw failure;}
            }
        } catch(RuntimeException failure) {
            if(lease==null) viewers.release(); else lease.resources.close();
            throw failure;
        }
    }
    private void beginTransport(Lease lease, BodySkin skin) {
        if(!eligible(lease)) {lease.resources.close();return;}
        NativePacketAdapter transport=null;
        try {
            lease.phase="WATCHING";
            transport=NativePacketAdapter.bind(plugin,version);
            NativePacketAdapter installed=transport;
            // Install first, publish second: retirement cannot close this adapter before watch inserts its session.
            CompletionStage<Void> watched=installed.watch(lease.viewer,(ignored,action)->dispatch(lease,action),input->interact(lease,input));
            lease.resources.transport(installed);
            if(!current(lease)) return;
            lease.transport=installed;
            lease.body=installed.createBody(UUID.randomUUID(),"minecraft:player","AnimusFolia",skin);
            lease.state=new VirtualNpcState(lease.body.uuid(),new WorldPosition(lease.worldId.toString(),lease.feet),lease.body.width(),lease.body.height(),20);
            watched.whenComplete((nothing,failure)->dispatch(lease,()->{
                if(failure!=null){fail(lease,"packet-watch-failed");return;}
                if(!eligible(lease)){lease.resources.close();return;}
                lease.phase="SPAWNING";
                installed.spawn(lease.viewer,lease.body,new BodyPose(lease.worldId.toString(),lease.feet.x(),lease.feet.y(),lease.feet.z(),lease.yaw,0,true))
                        .whenComplete((sent,problem)->dispatch(lease,()->{
                            if(problem!=null){fail(lease,"packet-spawn-failed");return;}
                            if(!eligible(lease)){lease.resources.close();return;}
                            lease.guard.bind(lease.body.entityId(),lease.state.spawn()); lease.visible=true; lease.phase="VISIBLE";
                            lease.viewer.sendMessage("Folia proof spawned id="+lease.body.entityId()+" uuid="+lease.body.uuid()+" generation="+lease.generation+" viewer-epoch="+lease.epoch.sequence()+" world="+lease.worldId
                                    +" x="+lease.feet.x()+" y="+lease.feet.y()+" z="+lease.feet.z()+" height="+lease.body.height()+" preview=true owner-checked=true");
                        }));
            }));
        } catch(ReflectiveOperationException | RuntimeException | LinkageError failure) {
            // Also covers a failed watch before resource publication.
            if(transport!=null) transport.close(); fail(lease,"native-binding-or-spawn-failed");
        }
    }
    private boolean dispatch(Lease lease,Runnable action) {
        if(!current(lease)) return false;
        return owner.execute(lease.viewer,()->{
            if(!current(lease)) return;
            try {owner.require(lease.viewer);action.run();}
            catch(RuntimeException failure){fail(lease,"owner-callback-failed");}
        },lease.resources::close);
    }
    private void poll(Lease lease) {
        try {if(!eligible(lease)) lease.resources.close();}
        catch(RuntimeException failure){lease.resources.close();}
    }
    private boolean eligible(Lease lease) {
        if(!current(lease)) return false;
        owner.require(lease.viewer);
        if(!lease.epoch.equals(epochs.capture(lease.viewer)))return false;
        Player viewer=lease.viewer;
        if(!viewer.isOnline() || viewer.isDead() || !viewer.hasPermission("animus.admin")
                || System.nanoTime()-lease.created>=LIFETIME || !viewer.getWorld().getUID().equals(lease.worldId)) return false;
        if(position(viewer.getLocation()).distance(lease.feet)>16) return false;
        return terrain.supported(viewer,lease.world,lease.feet,lease.body==null?.6:lease.body.width(),lease.body==null?1.8:lease.body.height());
    }
    private void interact(Lease lease,NativePacketAdapter.Interaction input) {
        if(!eligible(lease) || !lease.visible || lease.body!=input.body()) {lease.resources.close();return;}
        Location eye=lease.viewer.getEyeLocation(); Vector direction=eye.getDirection();
        var request=new ViewerSession.Request(lease.id,lease.guard.sessionId(),input.body().entityId(),
                new WorldPosition(lease.worldId.toString(),position(eye)),new Vec3(direction.getX(),direction.getY(),direction.getZ()),System.nanoTime());
        var validated=lease.guard.validate(request,(serverEye,point,distance)->terrain.lineOfSight(lease.viewer,lease.world,serverEye.point(),point,distance));
        if(!validated.accepted()) {lease.rejected++;lease.viewer.sendMessage("Folia proof rejected reason="+validated.rejection());return;}
        if(!current(lease)) return;
        var action=input.action()==NativePacketAdapter.Action.ATTACK?VirtualNpcInteractionEvent.Action.ATTACK:VirtualNpcInteractionEvent.Action.INTERACT;
        var event=new VirtualNpcInteractionEvent(lease.viewer,lease.body.uuid(),action,validated.distance(),true);
        plugin.getServer().getPluginManager().callEvent(event);
        // Listeners can clear/recreate/teleport. Re-read authority after callbacks, including the server ray.
        if(!eligible(lease) || !lease.visible || lease.state.snapshot().revision()!=validated.npc().revision()) return;
        Location after=lease.viewer.getEyeLocation(); Vector look=after.getDirection();
        var hit=lease.state.snapshot().bounds().rayDistance(position(after),new Vec3(look.getX(),look.getY(),look.getZ()),4.5);
        if(hit.isEmpty() || !terrain.lineOfSight(lease.viewer,lease.world,position(after),position(after).add(new Vec3(look.getX(),look.getY(),look.getZ()).normalized().multiply(hit.getAsDouble())),hit.getAsDouble())) return;
        if(event.isCancelled()){lease.viewer.sendMessage("Folia proof interaction cancelled id="+lease.body.entityId());return;}
        lease.accepted++; lease.viewer.sendMessage("Folia proof accepted id="+lease.body.entityId()+" action="+action+" preview=true");
        // This proof changes no health, command state, real entity or persisted definition.
    }
    private boolean current(Lease lease) {return !closed.get() && lease.resources.active() && sessions.get(lease.id)==lease;}
    private void fail(Lease lease,String reason) {
        boolean notify=current(lease) && owner.owns(lease.viewer);
        lease.resources.close();
        if(notify) lease.viewer.sendMessage("Folia proof retired reason="+reason);
    }
    private void retire(Player viewer) {
        pendingSpawns.invalidate(viewer);
        // At most eight leases. Exact object identity avoids retiring a reconnected player's new incarnation.
        for(Lease lease:sessions.values())if(lease.viewer==viewer)lease.resources.close();
    }
    @EventHandler public void quit(PlayerQuitEvent event){retire(event.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void teleport(PlayerTeleportEvent event){retire(event.getPlayer());}
    @EventHandler public void changedWorld(PlayerChangedWorldEvent event){retire(event.getPlayer());}
    @EventHandler public void respawn(PlayerRespawnEvent event){retire(event.getPlayer());}
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        pendingSpawns.close();commandWork.close();HandlerList.unregisterAll(this);sessions.values().forEach(lease->lease.resources.close());
    }
    private static Vec3 position(Location location){return new Vec3(location.getX(),location.getY(),location.getZ());}

    private final class Lease {
        final Player viewer; final UUID id,worldId; final World world; final Vec3 feet; final float yaw;
        final long created=System.nanoTime(),generation; final NativeViewerEpoch.Stamp epoch; final LeaseResources resources;
        final ViewerSession guard; final AtomicReference<SkinJob> skinJob=new AtomicReference<>();
        // Owner-confined fields. Retirement only closes resources, removes the concurrent directory entry and releases permits.
        NativePacketAdapter transport; NativePacketAdapter.Body body; VirtualNpcState state;
        boolean visible; String phase="ADMITTED"; long accepted,rejected;
        Lease(Player viewer,World world,Vec3 feet,float yaw,long generation,NativeViewerEpoch.Stamp epoch){
            this.viewer=viewer;id=viewer.getUniqueId();this.world=world;worldId=world.getUID();this.feet=feet;this.yaw=yaw;this.generation=generation;this.epoch=epoch;
            guard=new ViewerSession(id,UUID.randomUUID(),new ViewerSession.Limits(1,1,100_000_000L,4.5));
            resources=new LeaseResources(()->{sessions.remove(id,this);SkinJob job=skinJob.get();if(job!=null)job.cancelQueued();retirements.incrementAndGet();viewers.release();});
        }
    }
    private final class SkinJob implements Runnable {
        final Lease lease; final String hash; final AtomicInteger state=new AtomicInteger(); // 0 queued, 1 executing, 2 terminal
        SkinJob(Lease lease,String hash){this.lease=lease;this.hash=hash;}
        void cancelQueued(){if(state.compareAndSet(0,2))reads.release();}
        @Override public void run(){
            if(!state.compareAndSet(0,1))return;
            try {
                if(!current(lease))return;
                String text=documents.read("skins/"+hash+".json");
                if(text==null || text.length()>65536 || !JsonDocuments.hash(text).equals(hash))throw new IOException("Skin absent, oversized or hash changed");
                var json=JsonDocuments.object(text);
                if(!json.has("textureRaw") || !json.get("textureRaw").isJsonPrimitive() || !json.getAsJsonPrimitive("textureRaw").isString())throw new IOException("textureRaw must be a string");
                String signature=null;
                if(json.has("signature") && !json.get("signature").isJsonNull()){
                    if(!json.get("signature").isJsonPrimitive() || !json.getAsJsonPrimitive("signature").isString())throw new IOException("signature must be a string");
                    signature=json.get("signature").getAsString();
                }
                var skin=new BodySkin(json.get("textureRaw").getAsString(),signature);
                dispatch(lease,()->beginTransport(lease,skin));
            } catch(IOException | RuntimeException failure){dispatch(lease,()->fail(lease,"skin-read-failed"));}
            finally{state.set(2);reads.release();}
        }
    }
}
