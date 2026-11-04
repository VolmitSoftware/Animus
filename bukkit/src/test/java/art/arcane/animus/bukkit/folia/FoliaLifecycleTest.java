package art.arcane.animus.bukkit.folia;

import art.arcane.animus.core.virtual.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Lifecycle/geometry regressions independent of a live Bukkit server. */
@Timeout(15)
class FoliaLifecycleTest {
    @Test void closeBeforePublish() {
        AtomicInteger released=new AtomicInteger(), transport=new AtomicInteger(), task=new AtomicInteger();
        var scope=new LeaseResources(released::incrementAndGet);
        scope.close(); scope.transport(transport::incrementAndGet); scope.task(task::incrementAndGet); scope.close();
        require(!scope.active() && released.get()==1 && transport.get()==1 && task.get()==1,"retire before resources");
    }
    @Test void publishBeforeClose() {
        AtomicInteger released=new AtomicInteger(), transport=new AtomicInteger(), task=new AtomicInteger();
        var scope=new LeaseResources(released::incrementAndGet);
        scope.transport(transport::incrementAndGet);scope.task(task::incrementAndGet);scope.close();scope.close();
        require(released.get()==1 && transport.get()==1 && task.get()==1,"retire after resources");
    }
    @Test void throwingResourceStillReleases() {
        AtomicInteger released=new AtomicInteger(), transport=new AtomicInteger();
        var scope=new LeaseResources(released::incrementAndGet);
        scope.task(()->{throw new Exception("synthetic cancellation failure");});scope.transport(transport::incrementAndGet);scope.close();
        require(released.get()==1 && transport.get()==1 && !scope.active(),"one failed cleanup cannot skip another");
    }
    @Test void racePublication() throws Exception {
        for(int iteration=0;iteration<500;iteration++) {
            AtomicInteger released=new AtomicInteger(), transport=new AtomicInteger(), task=new AtomicInteger();
            var scope=new LeaseResources(released::incrementAndGet);var start=new CountDownLatch(1);
            Thread publish=new Thread(()->{await(start);scope.transport(transport::incrementAndGet);scope.task(task::incrementAndGet);});
            Thread retire=new Thread(()->{await(start);scope.close();});publish.start();retire.start();start.countDown();publish.join();retire.join();
            scope.close();require(released.get()==1 && transport.get()==1 && task.get()==1,"racing publication releases each resource exactly once");
        }
    }
    @Test void footprints() {
        var center=StationaryTerrain.body(new Vec3(.5,64,.5),.6,1.8);
        require(center.cells()==36 && center.minY()==63 && center.maxY()==66,"support and overhang halo included");
        var boundary=StationaryTerrain.body(new Vec3(15.9,64,15.9),.6,1.8);
        require((boundary.minX()>>4)==0 && (boundary.maxX()>>4)==1 && (boundary.minZ()>>4)==0 && (boundary.maxZ()>>4)==1,"whole rectangle crosses four chunks");
        var negative=StationaryTerrain.body(new Vec3(-.1,64,-.1),.6,1.8);
        require((negative.minX()>>4)==-1 && (negative.maxX()>>4)==0,"negative chunk coordinates use floor");
        var ray=StationaryTerrain.ray(new Vec3(15.5,65.6,15.5),new Vec3(18,65.6,18));
        require(ray.cells()<=512 && (ray.maxX()>>4)==1,"LOS ownership covers adjacent chunks and halo");
    }
    @Test void queuedRequestCancelled() {
        var pending=new PendingRequests<Object>(8);Object viewer=new Object();var before=pending.begin(viewer);
        AtomicInteger admitted=new AtomicInteger();Runnable callback=()->{if(pending.current(before))admitted.incrementAndGet();pending.finish(before);};
        pending.invalidate(viewer);callback.run();
        require(admitted.get()==0 && pending.size()==0,"teleport/clear before callback must suppress spawn admission");
        var after=pending.begin(viewer);pending.finish(before);
        require(after.generation()>before.generation() && pending.current(after),"old callback completion cannot revoke later spawn");
    }
    @Test void reconnectRequestsIsolated() {
        // Different connection objects deliberately compare equal, like identities sharing one account UUID.
        class Connection {public boolean equals(Object other){return other instanceof Connection;}public int hashCode(){return 1;}}
        var pending=new PendingRequests<Connection>(8);var oldViewer=new Connection();var newViewer=new Connection();
        var old=pending.begin(oldViewer);var replacement=pending.begin(newViewer);
        pending.invalidate(oldViewer);pending.finish(old);
        require(!pending.current(old) && pending.current(replacement),"old connection lifecycle cannot cancel reconnect replacement");
    }
    @Test void requestBounds() {
        var pending=new PendingRequests<Object>(8);var callbackSlots=new PendingRequests<Object>(8);
        Object[] viewers=new Object[8];
        for(int i=0;i<8;i++){viewers[i]=new Object();require(callbackSlots.begin(new Object())!=null && pending.begin(viewers[i])!=null,"admit bounded request");}
        require(pending.begin(new Object())==null && pending.size()==8,"ninth request rejected");
        pending.invalidate(viewers[0]);
        require(callbackSlots.begin(new Object())==null,"cancelling a ticket does not release its still-queued scheduler slot");
        require(pending.begin(viewers[1])==null,"one pending spawn per exact player object");
        callbackSlots.close();
        require(callbackSlots.size()==0 && callbackSlots.begin(new Object())==null,"shutdown cleans callback accounting even if Folia suppresses callbacks");
    }
    @Test void closeCancelsQueuedRequests() {
        var pending=new PendingRequests<Object>(8);Object viewer=new Object();var ticket=pending.begin(viewer);
        pending.close();pending.close();pending.finish(ticket);
        require(!pending.current(ticket) && pending.begin(viewer)==null && pending.size()==0,"shutdown invalidates queued spawn and denies later requests");
    }
    @Test void cancellationBeforePublication() throws Exception {
        var pending=new PendingRequests<Object>(8);Object viewer=new Object();var ticket=pending.begin(viewer);
        var directory=new ConcurrentHashMap<Object,LeaseResources>();var reference=new AtomicReference<LeaseResources>();
        AtomicInteger released=new AtomicInteger();
        var scope=new LeaseResources(()->{directory.remove(viewer,reference.get());released.incrementAndGet();});reference.set(scope);
        var checked=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
        Thread admit=new Thread(()->{
            require(pending.current(ticket),"initial admission accepted");checked.countDown();await(cancelled);
            directory.put(viewer,scope);
            if(!pending.current(ticket))scope.close();
        });
        admit.start();await(checked);pending.invalidate(viewer);
        require(directory.isEmpty(),"lifecycle scan occurs before lease publication");cancelled.countDown();admit.join();
        require(!scope.active() && released.get()==1 && directory.isEmpty(),"post-insertion recheck closes deterministically missed lease");
    }
    @Test void cancellationPublicationRace() throws Exception {
        for(int iteration=0;iteration<500;iteration++) {
            var pending=new PendingRequests<Object>(8);Object viewer=new Object();var ticket=pending.begin(viewer);
            var directory=new ConcurrentHashMap<Object,LeaseResources>();var reference=new AtomicReference<LeaseResources>();
            AtomicInteger released=new AtomicInteger();
            var scope=new LeaseResources(()->{directory.remove(viewer,reference.get());released.incrementAndGet();});reference.set(scope);
            var start=new CountDownLatch(1);
            Thread admit=new Thread(()->{
                await(start);
                if(!pending.current(ticket)){scope.close();return;}
                directory.put(viewer,scope);
                // Cancellation may have scanned the directory before this insertion.
                if(!pending.current(ticket))scope.close();
                pending.finish(ticket);
            });
            Thread cancel=new Thread(()->{
                await(start);pending.invalidate(viewer);
                var lease=directory.get(viewer);if(lease!=null)lease.close();
            });
            admit.start();cancel.start();start.countDown();admit.join();cancel.join();
            require(!scope.active() && released.get()==1 && directory.isEmpty(),"cancellation cannot miss a concurrently published lease");
        }
    }
    @Test void nativeEpochChangeCancelsQueuedSpawnWithoutLifecycleEvent() {
        record Epoch(Object connection,int counter) {}
        var pending=new PendingRequests<Object>(8);Object viewer=new Object(),connection=new Object();
        Epoch before=new Epoch(connection,17);var ticket=pending.begin(viewer,before);
        AtomicReference<Epoch> current=new AtomicReference<>(before);AtomicInteger admitted=new AtomicInteger();
        Runnable callback=()->{if(pending.current(ticket,current.get()))admitted.incrementAndGet();pending.finish(ticket);};
        // Same-position native teleport: no Bukkit event, no coordinate change, only server authority changes.
        current.set(new Epoch(connection,18));callback.run();
        require(admitted.get()==0 && pending.size()==0,"native epoch must revoke queued authority without a lifecycle event");
    }
    @Test void copiedEpochIsStableButAcknowledgedOrReconnectedEpochDoesNotReviveTicket() {
        record Epoch(Object connection,int counter) {}
        var pending=new PendingRequests<Object>(8);Object viewer=new Object(),connection=new Object();
        var ticket=pending.begin(viewer,new Epoch(connection,17));
        require(pending.current(ticket,new Epoch(connection,17)),"equivalent immutable capture retains request");
        require(!pending.current(ticket,new Epoch(connection,18)),"teleport changes request epoch");
        require(!pending.current(ticket,new Epoch(connection,18)),"client acknowledgement does not restore old epoch");
        require(!pending.current(ticket,new Epoch(new Object(),17)),"replacement connection cannot reuse original counter");
    }
    @Test void epochRecheckAfterLeasePublicationCatchesEventlessAdmissionChange() {
        record Epoch(Object connection,int counter) {}
        var pending=new PendingRequests<Object>(8);Object viewer=new Object(),connection=new Object();
        var ticket=pending.begin(viewer,new Epoch(connection,3));
        require(pending.current(ticket,new Epoch(connection,3)),"initial epoch admitted");
        AtomicInteger retired=new AtomicInteger();var lease=new LeaseResources(retired::incrementAndGet);
        Epoch after=new Epoch(connection,4);
        require(pending.current(ticket),"no lifecycle listener invalidated the identity ticket");
        if(!pending.current(ticket,after))lease.close();
        require(!lease.active() && retired.get()==1,"post-publication epoch check retires the newly inserted lease");
    }
    static void await(CountDownLatch latch){try{latch.await();}catch(InterruptedException e){throw new RuntimeException(e);}}
    static void require(boolean condition,String name){if(!condition)throw new AssertionError(name);}
}
