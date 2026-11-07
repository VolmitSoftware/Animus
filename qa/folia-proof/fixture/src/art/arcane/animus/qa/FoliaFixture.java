package art.arcane.animus.qa;

import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import java.lang.reflect.Field;
import io.netty.channel.Channel;
import art.arcane.animus.core.virtual.Vec3;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

/** QA only. No world task or global entity lookup is used to assert ownership. */
public final class FoliaFixture extends JavaPlugin implements Listener {
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final AtomicLong ownerChecks = new AtomicLong(), rectangleChecks = new AtomicLong();
    private final AtomicLong events = new AtomicLong(), failures = new AtomicLong(), scheduled = new AtomicLong();
    private final AtomicLong chunkEvents=new AtomicLong();
    private final AtomicReference<CountDownLatch> ioHold=new AtomicReference<>();
    private volatile Object shutdownRuntime;
    private volatile java.lang.reflect.Method shutdownFileStatus;
    private final Map<UUID,Probe> probes=new ConcurrentHashMap<>();
    private final Set<UUID> joinProbes=ConcurrentHashMap.newKeySet();
    private final AtomicReference<CountDownLatch> networkHold=new AtomicReference<>();
    private record Probe(UUID world,int x,int z,AtomicLong changes) {}
    private static final class State {
        int slot;
        UUID expected;
        String action = "allow";
        long events;
    }
    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }
    @Override public void onDisable() { CountDownLatch hold=ioHold.getAndSet(null);if(hold!=null)hold.countDown();hold=networkHold.getAndSet(null);if(hold!=null)hold.countDown();states.clear();probes.clear();joinProbes.clear();shutdownRuntime=null;shutdownFileStatus=null; }
    private static Field field(Class<?> type,String name) throws ReflectiveOperationException {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private Channel channel(Player p) throws ReflectiveOperationException {
        own(p);Object handle=p.getClass().getMethod("getHandle").invoke(p);
        Object listener=handle.getClass().getField("connection").get(handle);
        Object connection=field(Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl"),"connection").get(listener);
        return (Channel)field(Class.forName("net.minecraft.network.Connection"),"channel").get(connection);
    }
    private int teleportEpoch(Player p) throws ReflectiveOperationException {
        own(p);Object handle=p.getClass().getMethod("getHandle").invoke(p);
        Object listener=handle.getClass().getField("connection").get(handle);
        return field(Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl"),"awaitingTeleport").getInt(listener);
    }
    private void transport(Player p) {
        try {
            Channel channel=channel(p);
            channel.eventLoop().execute(()->{
                long handlers=channel.pipeline().names().stream().filter(n->n.startsWith("animus_virtual_")).count();
                later(p,()->p.sendMessage("Folia QA transport handlers="+handlers+" event-loop-barrier=true"));
            });
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private void holdIo(Player p) {
        CountDownLatch hold=new CountDownLatch(1);
        if(!ioHold.compareAndSet(null,hold))throw new IllegalStateException("file worker already held");
        try {
            Object plugin=getServer().getPluginManager().getPlugin("Animus");
            Executor executor=(Executor)field(plugin.getClass(),"commands").get(plugin);
            executor.execute(()->{
                later(p,()->p.sendMessage("Folia QA file worker held timeout-seconds=20"));
                try {hold.await(20,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                finally {ioHold.compareAndSet(hold,null);}
            });
        }catch(ReflectiveOperationException|RuntimeException e){ioHold.compareAndSet(hold,null);hold.countDown();throw new IllegalStateException(e);}
    }
    private void stallClear(Player p) {
        try {
            Channel channel=channel(p);
            channel.eventLoop().execute(()->{try{Thread.sleep(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            animus(p,"proof","folia","spawn");
            delayed(p,4,()->{animus(p,"proof","folia","clear");p.sendMessage("Folia QA watch stalled then cleared owner=true");},()->{});
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private Object service() throws ReflectiveOperationException {
        Object plugin=getServer().getPluginManager().getPlugin("Animus");
        return field(plugin.getClass(),"foliaProof").get(plugin);
    }
    private void closeService(Player p) {
        try {
            Object service=service();String before=(String)service.getClass().getMethod("status").invoke(service);
            long start=System.nanoTime();service.getClass().getMethod("close").invoke(service);long elapsed=System.nanoTime()-start;
            String after=(String)service.getClass().getMethod("status").invoke(service);
            boolean accepted=(boolean)service.getClass().getMethod("execute",Player.class,String.class,String.class).invoke(service,p,"spawn",null);
            p.sendMessage("Folia QA service close nanos="+elapsed+" new-request-accepted="+accepted+" before={"+before+"} after={"+after+"}");
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private void pendingClose(Player p) {
        try {
            Channel channel=channel(p);
            channel.eventLoop().execute(()->{try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            animus(p,"proof","folia","spawn");
            delayed(p,4,()->closeService(p),()->{});
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private void pendingDisable(Player p) {
        try {
            Channel channel=channel(p);
            channel.eventLoop().execute(()->{try{Thread.sleep(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            animus(p,"proof","folia","spawn");
            delayed(p,4,()->{
                // A bounded owner hold proves global disable does not wait on it.
                for(int i=0;i<8;i++)animus(p,"proof","folia","status");
                CountDownLatch released=new CountDownLatch(1);
                getServer().getGlobalRegionScheduler().run(this,ignored->{
                    try {
                        Object service=service();String before=(String)service.getClass().getMethod("status").invoke(service);
                        var plugin=getServer().getPluginManager().getPlugin("Animus");
                        shutdownRuntime=Objects.requireNonNull(field(plugin.getClass(),"runtime").get(plugin));
                        // Resolve reflection metadata and the diagnostic return type while
                        // the platform still keeps this plugin's JAR/classloader open.
                        shutdownFileStatus=shutdownRuntime.getClass().getMethod("fileStatus");
                        getLogger().info("Folia QA file status before disable "+shutdownFileStatus.invoke(shutdownRuntime));
                        long start=System.nanoTime();getServer().getPluginManager().disablePlugin(plugin);long elapsed=System.nanoTime()-start;
                        String after=(String)service.getClass().getMethod("status").invoke(service);
                        String receipt="Folia QA plugin disable nanos="+elapsed+" enabled="+plugin.isEnabled()+" before={"+before+"} after={"+after+"}";
                        getLogger().info(receipt);later(p,()->p.sendMessage(receipt));
                    }catch(ReflectiveOperationException|RuntimeException failure){failures.incrementAndGet();getLogger().severe("Folia QA disable failed: "+failure);}
                    finally{released.countDown();}
                });
                try {if(!released.await(2,TimeUnit.SECONDS))throw new IllegalStateException("global plugin disable waited on blocked owner");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            },()->{});
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private void joinFootprint(Player p) {
        own(p);Location l=p.getLocation();World w=l.getWorld();
        var facing=l.getDirection().setY(0);if(facing.lengthSquared()<1e-6)facing=new org.bukkit.util.Vector(0,0,1);
        Location desired=l.clone().add(facing.normalize().multiply(3));int x=desired.getBlockX(),z=desired.getBlockZ();
        boolean owned=getServer().isOwnedByCurrentRegion(w,(x-1)>>4,(z-1)>>4,(x+1)>>4,(z+1)>>4);
        boolean loaded=owned,sent=owned;
        if(owned)for(int cx=(x-1)>>4;cx<=(x+1)>>4;cx++)for(int cz=(z-1)>>4;cz<=(z+1)>>4;cz++) {
            loaded&=w.isChunkLoaded(cx,cz);sent&=p.isChunkSent(((long)cz<<32)|(cx&0xffffffffL));
        }
        p.sendMessage("Folia QA join footprint owner="+owned+" loaded="+loaded+" sent="+sent+" x="+x+" z="+z+" observed-before-admission=true");
    }
    private void retirementBurst(Player p) {
        try {
            CountDownLatch hold=new CountDownLatch(1);
            if(!networkHold.compareAndSet(null,hold))throw new IllegalStateException("network stall already pending");
            Channel channel=channel(p);Object service=service();
            channel.eventLoop().execute(()->{try{hold.await(12,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{hold.countDown();networkHold.compareAndSet(hold,null);}});
            burstStep(p,service,hold,0);
        }catch(ReflectiveOperationException e){CountDownLatch hold=networkHold.getAndSet(null);if(hold!=null)hold.countDown();throw new IllegalStateException(e);}
    }
    private void burstStep(Player p,Object service,CountDownLatch hold,int attempted) {
        own(p);
        if(hold.getCount()==0)throw new IllegalStateException("network watchdog released before retirement burst finished");
        try {
            String status=(String)service.getClass().getMethod("status").invoke(service);
            var matcher=java.util.regex.Pattern.compile("lifetime admissions=(\\d+)/64").matcher(status);
            if(!matcher.find())throw new IllegalStateException("missing admissions counter");
            boolean exhausted=Integer.parseInt(matcher.group(1))==64;
            animus(p,"proof","folia","spawn");
            delayed(p,2,()->{
                animus(p,"proof","folia","clear");
                if(!exhausted){burstStep(p,service,hold,attempted+1);return;}
                try {
                    String terminal=(String)service.getClass().getMethod("status").invoke(service);
                    if(!terminal.contains("lifetime admissions=64/64")||!terminal.contains("admitted=0/8"))throw new IllegalStateException("burst accounting failed");
                    hold.countDown();p.sendMessage("Folia QA retirement burst attempts="+(attempted+1)+" publication-stalled=true watchdog-seconds=12 "+terminal);
                }catch(ReflectiveOperationException failure){hold.countDown();throw new IllegalStateException(failure);}
            },hold::countDown);
        }catch(ReflectiveOperationException|RuntimeException failure){hold.countDown();throw new IllegalStateException(failure);}
    }
    private void probe(Player p) {
        own(p);World world=p.getWorld();int x=1_000_000,z=1_000_000;
        if(getServer().isOwnedByCurrentRegion(world,x>>4,z>>4,x>>4,z>>4))throw new IllegalStateException("probe must be unowned");
        UUID viewer=p.getUniqueId();Probe audit=new Probe(world.getUID(),x>>4,z>>4,new AtomicLong());
        if(probes.putIfAbsent(viewer,audit)!=null)throw new IllegalStateException("probe already pending");
        try {
            int[] before=tickets(world,x>>4,z>>4);
            Object plugin=getServer().getPluginManager().getPlugin("Animus");
            Object service=field(plugin.getClass(),"foliaProof").get(plugin);
            Object terrain=field(service.getClass(),"terrain").get(service);
            var supported=terrain.getClass().getDeclaredMethod("supported",Player.class,World.class,Vec3.class,double.class,double.class);supported.setAccessible(true);
            boolean allowed=(boolean)supported.invoke(terrain,p,world,new Vec3(x+.5,61,z+.5),.6,1.8);
            if(allowed)throw new IllegalStateException("unowned footprint was admitted");
            delayed(p,20,()->{
                probes.remove(viewer,audit);
                try {
                    int[] after=tickets(world,x>>4,z>>4);
                    p.sendMessage("Folia QA terrain probe admitted=false owned=false target-chunk-events="+audit.changes.get()+" observed-owner-ticks=20 total-chunk-events="+chunkEvents.get()+" no-terrain-read-before-ownership=source-reviewed tickets-before="+counts(before)+" tickets-after="+counts(after)+" transient-ticket-absence=unverified");
                }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
            },()->probes.remove(viewer,audit));
        }catch(ReflectiveOperationException|RuntimeException e){probes.remove(viewer,audit);throw new IllegalStateException(e);}
    }
    private static String counts(int[] values) {return java.util.Arrays.toString(values).replace(" ","").replace("[","").replace("]","");}
    private int[] tickets(World world,int x,int z) throws ReflectiveOperationException {
        // Exact native API reviewed on all3: getTicketsAt takes its per-chunk
        // ticket lock and returns a fresh list. It does not load/schedule terrain.
        Object handle=world.getClass().getMethod("getHandle").invoke(world);
        Object scheduler=handle.getClass().getMethod("moonrise$getChunkTaskScheduler").invoke(handle);
        Object manager=scheduler.getClass().getField("chunkHolderManager").get(scheduler);
        var get=manager.getClass().getMethod("getTicketsAt",int.class,int.class);
        int[] counts=new int[9];int index=0;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)counts[index++]=((List<?>)get.invoke(manager,x+dx,z+dz)).size();
        return counts;
    }
    private void own(Player p) {
        if (!getServer().isOwnedByCurrentRegion(p)) { failures.incrementAndGet(); throw new IllegalStateException("viewer owner required"); }
        ownerChecks.incrementAndGet();
    }
    private void rectangle(Player p, World w, int minX, int minZ, int maxX, int maxZ, boolean sent) {
        own(p);
        if (!getServer().isOwnedByCurrentRegion(w, minX >> 4, minZ >> 4, maxX >> 4, maxZ >> 4))
            throw new IllegalStateException("rectangle not owned");
        rectangleChecks.incrementAndGet();
        for (int x=minX>>4; x<=maxX>>4; x++) for (int z=minZ>>4; z<=maxZ>>4; z++) {
            if (!w.isChunkLoaded(x,z)) throw new IllegalStateException("rectangle not loaded");
            if (sent && !p.isChunkSent(((long)z<<32)|(x&0xffffffffL))) throw new IllegalStateException("rectangle not delivered");
        }
    }
    private void later(Player p, Runnable action) {
        scheduled.incrementAndGet();
        AtomicBoolean released=new AtomicBoolean();
        Runnable release=()->{if(released.compareAndSet(false,true))scheduled.decrementAndGet();};
        boolean queued = p.getScheduler().execute(this, () -> {
            release.run();
            try { own(p); action.run(); }
            catch (Exception failure) { failures.incrementAndGet(); if(getServer().isOwnedByCurrentRegion(p))p.sendMessage("Folia QA ERROR "+failure.getClass().getSimpleName()+": "+failure.getMessage()); }
        }, release, 1L);
        if (!queued) {release.run();getLogger().info("Folia QA owner callback retired before admission");}
    }
    private void delayed(Player p,long ticks,Runnable action,Runnable retired) {
        scheduled.incrementAndGet();AtomicBoolean released=new AtomicBoolean();
        Runnable release=()->{if(released.compareAndSet(false,true))scheduled.decrementAndGet();};
        Runnable retirement=()->{release.run();retired.run();};
        try {
            var task=p.getScheduler().runDelayed(this,ignored->{release.run();try{own(p);action.run();}catch(RuntimeException e){failures.incrementAndGet();if(getServer().isOwnedByCurrentRegion(p))p.sendMessage("Folia QA ERROR "+e.getMessage());}},retirement,ticks);
            if(task==null)retirement.run();
        }catch(RuntimeException e){retirement.run();throw e;}
    }
    private State state(Player p) { own(p); return states.computeIfAbsent(p.getUniqueId(), ignored -> new State()); }
    private void animus(Player p, String... args) {
        own(p);
        Objects.requireNonNull(getServer().getPluginCommand("animus")).execute(p,"animus",args);
    }
    private long region() throws ReflectiveOperationException {
        Object current=Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler").getMethod("getCurrentRegion").invoke(null);
        if (current==null) throw new IllegalStateException("no current region");
        return current.getClass().getField("id").getLong(current);
    }
    private void prepare(Player p, int slot, int attempt) {
        own(p);
        int base=slot*8192;
        World w=p.getWorld();
        try { rectangle(p,w,base+4,4,base+12,12,true); }
        catch (IllegalStateException waiting) {
            if (attempt>=100) throw waiting;
            later(p,()->prepare(p,slot,attempt+1)); return;
        }
        // Every live read/write has a fresh full-rectangle admission and cell check.
        for (int x=base+4;x<=base+12;x++) for(int z=4;z<=12;z++) for(int y=60;y<=65;y++) {
            rectangle(p,w,x,z,x,z,true);
            w.getBlockAt(x,y,z).setType(y==60?Material.STONE:Material.AIR,false);
        }
        own(p); p.setGameMode(GameMode.CREATIVE); p.setInvulnerable(true); p.setFoodLevel(20);
        try { p.sendMessage("Folia QA ready slot="+slot+" region="+region()+" owner=true rectangle=true loaded=true sent=true x="+(base+8.5)+" y=61.0 z=8.5 health="+p.getHealth()); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private void setup(Player p,int slot,String targetWorld) {
        if(slot<0||slot>12) throw new IllegalArgumentException("slot 0..12 required");
        State s=state(p); s.slot=slot; s.expected=null; s.action="allow";
        World w=targetWorld==null?p.getWorld():Objects.requireNonNull(getServer().getWorld(targetWorld)); own(p);
        p.teleportAsync(new Location(w,slot*8192+8.5,61,8.5,0,0)).whenComplete((ok,fail)->later(p,()->{
            if(fail!=null||!Boolean.TRUE.equals(ok)) throw new IllegalStateException("setup teleport failed");
            prepare(p,slot,0);
        }));
    }
    private void block(Player p, boolean wall, boolean enabled) {
        State s=state(p); World w=p.getWorld(); int x=s.slot*8192+8;
        int z=wall?10:11;
        for(int y=wall?61:60;y<=(wall?63:60);y++) {
            rectangle(p,w,x,z,x,z,true);
            w.getBlockAt(x,y,z).setType(enabled?(wall?Material.STONE:Material.AIR):(wall?Material.AIR:Material.STONE),false);
        }
        p.sendMessage("Folia QA "+(wall?"wall":"hole")+"="+enabled+" owner=true loaded=true sent=true");
    }
    private int registered(Player p,UUID id) {
        own(p); Location l=p.getLocation(); World w=l.getWorld();
        int x=l.getBlockX(),z=l.getBlockZ();
        rectangle(p,w,x-6,z-6,x+6,z+6,true);
        int count=0;
        for(Entity entity:w.getNearbyEntities(new BoundingBox(l.getX()-6,l.getY()-3,l.getZ()-6,l.getX()+6,l.getY()+5,l.getZ()+6))) {
            if(!getServer().isOwnedByCurrentRegion(entity)) throw new IllegalStateException("nearby entity not owned");
            ownerChecks.incrementAndGet(); if(entity.getUniqueId().equals(id))count++;
        }
        return count;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(args.length==1&&args[0].equals("servicestatus")&&!(sender instanceof Player)) {
            try{Object service=service();sender.sendMessage("Folia QA retained "+service.getClass().getMethod("status").invoke(service));}
            catch(ReflectiveOperationException e){failures.incrementAndGet();throw new IllegalStateException(e);}return true;
        }
        if(args.length==1&&args[0].equals("counters")&&!(sender instanceof Player)) {
            sender.sendMessage("Folia QA counters owners="+ownerChecks.get()+" rectangles="+rectangleChecks.get()+" events="+events.get()+" failures="+failures.get()+" scheduled="+scheduled.get()+" states="+states.size()+" probes="+probes.size()+" joins="+joinProbes.size()+" io-held="+(ioHold.get()==null?0:1)+" network-held="+(networkHold.get()==null?0:1));return true;
        }
        if(!(sender instanceof Player p)) { sender.sendMessage("Folia QA player required");return true; }
        String[] request=args.clone();
        later(p,()->{
            if(!p.hasPermission("animus.admin")||!p.getName().matches("AnimusFolia[0-9]{1,2}"))throw new IllegalStateException("synthetic operator required");
            if(request.length==0)throw new IllegalArgumentException("operation required");
            State s=state(p);
            switch(request[0]) {
                case "setup" -> setup(p,Integer.parseInt(request[1]),request.length>2?request[2]:null);
                case "expect" -> { s.expected=UUID.fromString(request[1]);p.sendMessage("Folia QA expected uuid="+s.expected+" world-registered="+registered(p,s.expected)+" owner=true"); }
                case "arm" -> { if(!Set.of("allow","cancel","clear","replace","teleport","teleportsame").contains(request[1]))throw new IllegalArgumentException("invalid action");s.action=request[1];p.sendMessage("Folia QA armed="+s.action); }
                case "wall" -> block(p,true,Boolean.parseBoolean(request[1]));
                case "hole" -> block(p,false,Boolean.parseBoolean(request[1]));
                case "queueclear" -> { if(request.length>1)animus(p,"proof","folia","spawn",request[1]);else animus(p,"proof","folia","spawn");animus(p,"proof","folia","clear");p.sendMessage("Folia QA queued spawn then clear owner=true"); }
                case "teleport" -> { own(p);p.teleportAsync(new Location(p.getWorld(),s.slot*8192+8.5,61,8.5,0,0));p.sendMessage("Folia QA teleport requested"); }
                case "transport" -> transport(p);
                case "iohold" -> holdIo(p);
                case "iorelease" -> { CountDownLatch hold=ioHold.get();if(hold!=null)hold.countDown();p.sendMessage("Folia QA file worker released"); }
                case "filestatus" -> {
                    try {
                        Object runtime=Objects.requireNonNull(shutdownRuntime,"No captured shutdown runtime");
                        // Pure diagnostics only: never await the file worker on a region owner.
                        Object status=Objects.requireNonNull(shutdownFileStatus).invoke(runtime);
                        p.sendMessage("Folia QA file status io-held="+(ioHold.get()==null?0:1)+" "+status);
                    }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
                }
                case "die" -> { own(p);p.setInvulnerable(false);p.setHealth(0); }
                case "permission" -> {
                    own(p);var denied=p.addAttachment(this,"animus.admin",false);
                    p.sendMessage("Folia QA permission revoked");
                    delayed(p,20,()->{p.removeAttachment(denied);p.sendMessage("Folia QA permission restored");},()->{});
                }
                case "queueteleport" -> { animus(p,"proof","folia","spawn");own(p);p.teleportAsync(p.getLocation().add(request.length>1&&request[1].equals("same")?0:1,0,0)).whenComplete((ok,problem)->later(p,()->p.sendMessage("Folia QA queued teleport completed success="+ok))); }
                case "visibleteleport" -> { own(p);p.teleportAsync(p.getLocation().add(request.length>1&&request[1].equals("same")?0:1,0,0)).whenComplete((ok,problem)->later(p,()->p.sendMessage("Folia QA visible teleport completed success="+ok))); }
                case "stallclear" -> stallClear(p);
                case "pendingclose" -> pendingClose(p);
                case "pendingdisable" -> pendingDisable(p);
                case "retireburst" -> retirementBurst(p);
                case "closedstatus" -> {
                    try {Object service=service();boolean accepted=(boolean)service.getClass().getMethod("execute",Player.class,String.class,String.class).invoke(service,p,"spawn",null);
                        p.sendMessage("Folia QA closed new-request-accepted="+accepted+" "+service.getClass().getMethod("status").invoke(service));
                    }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
                }
                case "joinprobe" -> { joinProbes.add(p.getUniqueId());p.sendMessage("Folia QA next join probe armed"); }
                case "queuequit" -> { animus(p,"proof","folia","spawn");p.sendMessage("Folia QA queued spawn then kick");p.kick(net.kyori.adventure.text.Component.text("Folia QA retirement")); }
                case "probe" -> probe(p);
                case "commandburst" -> { for(int i=0;i<9;i++)animus(p,"proof","folia","status");p.sendMessage("Folia QA queued status callbacks=9"); }
                case "health" -> { own(p);p.sendMessage("Folia QA health="+p.getHealth()+" events="+s.events+" owner=true"); }
                case "position" -> { own(p);Location l=p.getLocation();p.sendMessage("Folia QA position x="+l.getX()+" y="+l.getY()+" z="+l.getZ()+" yaw="+l.getYaw()+" owner=true"); }
                case "cleanup" -> { animus(p,"proof","folia","clear");states.remove(p.getUniqueId());probes.remove(p.getUniqueId());p.sendMessage("Folia QA cleaned"); }
                default -> throw new IllegalArgumentException("unknown operation");
            }
        });return true;
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void interaction(VirtualNpcInteractionEvent event) {
        Player p=event.getViewer();
        try {
            own(p);State s=states.get(p.getUniqueId());if(s==null||!event.getNpcId().equals(s.expected))return;
            if(!event.isPreview())throw new IllegalStateException("preview flag missing");
            int count=registered(p,s.expected);if(count!=0)throw new IllegalStateException("virtual body registered");
            double health=p.getHealth();events.incrementAndGet();s.events++;
            p.sendMessage("Folia QA event uuid="+event.getNpcId()+" action="+event.getAction()+" preview=true owner=true world-registered=0 health="+health+" region="+region());
            String action=s.action;s.action="allow";
            switch(action) {
                case "cancel" -> { event.setCancelled(true);p.sendMessage("Folia QA cancelled interaction"); }
                case "clear" -> { animus(p,"proof","folia","clear");p.sendMessage("Folia QA cleared during event"); }
                case "replace" -> { animus(p,"proof","folia","clear");animus(p,"proof","folia","spawn");p.sendMessage("Folia QA replaced during event"); }
                case "teleport" -> { own(p);p.teleportAsync(p.getLocation().add(1,0,0));p.sendMessage("Folia QA teleported during event"); }
                case "teleportsame" -> { int before=teleportEpoch(p);p.teleportAsync(p.getLocation());int after=teleportEpoch(p);p.sendMessage("Folia QA same-position event teleport epoch-before="+before+" epoch-after="+after); }
                default -> {}
            }
        } catch(Exception failure) {
            failures.incrementAndGet();event.setCancelled(true);
            getLogger().severe("Folia QA interaction ownership failure: "+failure);
            if(getServer().isOwnedByCurrentRegion(p))p.sendMessage("Folia QA ERROR "+failure.getMessage());
        }
    }
    @EventHandler public void quit(PlayerQuitEvent event) { own(event.getPlayer());UUID id=event.getPlayer().getUniqueId();states.remove(id);probes.remove(id); }
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent event) {
        Player p=event.getPlayer();own(p);
        if(!joinProbes.remove(p.getUniqueId()))return;
        // FIFO next-owner-tick observation precedes the actual production spawn callback.
        later(p,()->joinFootprint(p));animus(p,"proof","folia","spawn");
    }
    @EventHandler public void chunkLoad(ChunkLoadEvent event) { observeChunk(event.getChunk()); }
    @EventHandler public void chunkUnload(ChunkUnloadEvent event) { observeChunk(event.getChunk()); }
    private void observeChunk(Chunk chunk) {
        // Event location identity is immutable; terrain/entity data is never inspected here.
        World world=chunk.getWorld();int x=chunk.getX(),z=chunk.getZ();
        if(!getServer().isOwnedByCurrentRegion(world,x,z,x,z)){failures.incrementAndGet();return;}
        chunkEvents.incrementAndGet();UUID worldId=world.getUID();
        for(Probe probe:probes.values())if(probe.world.equals(worldId)&&Math.abs(x-probe.x)<=1&&Math.abs(z-probe.z)<=1)probe.changes.incrementAndGet();
    }
}
