package art.arcane.animus.core.extension;

import art.arcane.animus.api.Outcome;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Serial durable mutation, bounded acknowledgment delivery, and engine-owned termination cleanup.
 * Engine observers/cleanup are quick trusted engine hooks, not extension callback surfaces.
 */
public final class MutationLane implements AutoCloseable {
    private static final int MAXIMUM_LIMIT=1024;
    public enum CleanupState { NOT_SCHEDULED, QUEUED, RUNNING, SUCCEEDED, FAILED }
    public enum EngineTaskEvent { CANCELLED, FAILED }
    @FunctionalInterface public interface EngineTaskObserver {
        /** Runs on the mutation worker. Cancellation has a null failure; notifications must be quick and nonblocking. */
        void report(Runnable task,EngineTaskEvent event,Throwable failure);
    }
    public record Status(int admitted,int queuedMutations,int runningMutations,int queuedDeliveries,
                         int activeDeliveries,int overBudgetDeliveries,long completedSlowDeliveries,
                         boolean closed,boolean mutationsDrained,boolean drainContinuationActive,
                         boolean drainContinuationOverBudget,int queuedEngineNotifications,
                         long failedEngineTasks,long cancelledEngineTasks,long observerFailures,
                         CleanupState cleanupState,String lastEngineFailure,boolean quiescent) {}
    /** Guarded by this lane; {@link #limits} replaces both live. */
    private int maximum;
    private long deliveryBudgetNanos;
    private final ThreadPoolExecutor worker,deliveries;
    private final ExecutorService drainSignal;
    private final Runnable cleanup;
    private final Consumer<Throwable> cleanupFailureObserver;
    private final Set<Work> active=new HashSet<>();
    private final CompletableFuture<Void> drained=new CompletableFuture<>();
    private int pendingMutations;
    private long slowDeliveries,drainStartedNanos,failedEngineTasks,cancelledEngineTasks,observerFailures;
    private boolean closed,drainScheduled,drainActive,drainNotificationFinished;
    private CleanupState cleanupState=CleanupState.NOT_SCHEDULED;
    private String lastEngineFailure="none";

    public MutationLane(int maximum){this(maximum,Duration.ofMillis(250));}
    public MutationLane(int maximum,Duration deliveryBudget){this(maximum,deliveryBudget,()->{},failure->{});}
    public MutationLane(int maximum,Duration deliveryBudget,Runnable cleanup,Consumer<Throwable> cleanupFailureObserver) {
        check(maximum,deliveryBudget);
        this.maximum=maximum;deliveryBudgetNanos=deliveryBudget.toNanos();
        this.cleanup=Objects.requireNonNull(cleanup);this.cleanupFailureObserver=Objects.requireNonNull(cleanupFailureObserver);
        worker=pool(1,"Animus-api-files");
        deliveries=pool(2,"Animus-api-delivery");
        drainSignal=pool(1,"Animus-api-drain");
    }
    private static void check(int maximum,Duration deliveryBudget){
        if(maximum<1 || maximum>MAXIMUM_LIMIT || deliveryBudget==null || deliveryBudget.isZero() || deliveryBudget.isNegative()
                || deliveryBudget.compareTo(Duration.ofMinutes(1))>0)throw new IllegalArgumentException("Invalid mutation/delivery limits");
    }
    /**
     * Replaces the admission limit and delivery budget live. Admitted work always finishes; a lower limit only holds new
     * work back (callers see the usual busy answer) until enough admitted work has ended.
     */
    public synchronized void limits(int maximum,Duration deliveryBudget){
        check(maximum,deliveryBudget);
        this.maximum=maximum;deliveryBudgetNanos=deliveryBudget.toNanos();
    }
    private static ThreadFactory factory(String name){return job->{Thread thread=new Thread(job,name);thread.setDaemon(true);return thread;};}
    /** Admission bounds every queue, so the queues themselves need no capacity of their own and follow a live limit. */
    private static ThreadPoolExecutor pool(int threads,String name){return new ThreadPoolExecutor(threads,threads,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>(),factory(name));}
    public <T> CompletionStage<Outcome<T>> submit(Supplier<Outcome<T>> operation) {
        ApiWork<T> task=new ApiWork<>(Objects.requireNonNull(operation));
        synchronized(this) {
            if(closed)return rejected(Outcome.Status.UNAVAILABLE,"service-closed","Definition service is closed");
            if(active.size()>=maximum)return rejected(Outcome.Status.BUSY,"mutation-delivery-capacity","Mutation or acknowledgment delivery capacity reached");
            admitLocked(task);
        }
        return task.result.minimalCompletionStage();
    }
    /** No private queue: internal commands/read tasks share the same worker and end-to-end admission limit as APIs. */
    public Executor executor(EngineTaskObserver observer){
        Objects.requireNonNull(observer);
        return command->{
            EngineWork task=new EngineWork(Objects.requireNonNull(command),observer);
            synchronized(this){
                if(closed)throw new RejectedExecutionException("Mutation lane is closed");
                if(active.size()>=maximum)throw new RejectedExecutionException("Mutation/delivery capacity reached");
                admitLocked(task);
            }
        };
    }
    private void admitLocked(Work task){active.add(task);pendingMutations++;worker.execute(task);}
    private static <T> CompletionStage<Outcome<T>> rejected(Outcome.Status status,String code,String message){
        return CompletableFuture.completedFuture(Outcome.<T>rejected(status,code,message)).minimalCompletionStage();
    }
    /** Convenience notification only; arbitrary derived stages retain cooperative CompletionStage semantics. */
    public CompletionStage<Void> drained(){return drained.minimalCompletionStage();}
    /** Mutation-only gate includes queued engine cancellation notifications, but excludes cleanup and public delivery. */
    public boolean awaitMutations(Duration timeout)throws InterruptedException{return awaitState(timeout,false);}
    /** Authoritative engine cleanup gate. A true result means cleanup ended; inspect cleanupState for success/failure. */
    public boolean awaitCleanup(Duration timeout)throws InterruptedException{return awaitState(timeout,true);}
    /** Restart gate, including every admitted result listener and the queued/running drain notification. */
    public synchronized boolean awaitQuiescence(Duration timeout)throws InterruptedException {
        if(timeout==null || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(1))>0)throw new IllegalArgumentException("Quiescence wait must be 0..60 seconds");
        long budget=timeout.toNanos(),started=System.nanoTime(),remaining=budget;
        while(!quiescentLocked()){
            if(remaining<=0)return false;
            TimeUnit.NANOSECONDS.timedWait(this,remaining);remaining=budget-(System.nanoTime()-started);
        }
        return true;
    }
    private boolean quiescentLocked() {
        return (cleanupState==CleanupState.SUCCEEDED || cleanupState==CleanupState.FAILED)
                && active.isEmpty() && drainNotificationFinished;
    }
    private synchronized boolean awaitState(Duration timeout,boolean includeCleanup)throws InterruptedException{
        if(timeout==null || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(1))>0)throw new IllegalArgumentException("Drain wait must be 0..60 seconds");
        long budget=timeout.toNanos(),started=System.nanoTime(),remaining=budget;
        while(includeCleanup?cleanupState!=CleanupState.SUCCEEDED&&cleanupState!=CleanupState.FAILED:!closed||pendingMutations!=0){
            if(remaining<=0)return false;
            TimeUnit.NANOSECONDS.timedWait(this,remaining);remaining=budget-(System.nanoTime()-started);
        }
        return true;
    }
    /** Observational deadlines: slow delivery is reported, never preempted or silently released. */
    public synchronized Status status(){
        int queued=0,running=0,deliveryQueued=0,delivering=0,slow=0,notices=0;long now=System.nanoTime();
        for(Work task:active)switch(task.phase){
            case 0->queued++;
            case 1->running++;
            case 2->deliveryQueued++;
            case 3->{delivering++;if(now-task.deliveryStartedNanos>=deliveryBudgetNanos)slow++;}
            case 4->notices++;
            default->throw new IllegalStateException("Invalid task state");
        }
        return new Status(active.size(),queued,running,deliveryQueued,delivering,slow,slowDeliveries,closed,
                closed&&pendingMutations==0,drainActive,drainActive&&now-drainStartedNanos>=deliveryBudgetNanos,
                notices,failedEngineTasks,cancelledEngineTasks,observerFailures,cleanupState,lastEngineFailure,quiescentLocked());
    }
    @Override public void close() {
        synchronized(this) {
            if(closed)return;closed=true;
            for(Work task:List.copyOf(active))if(task.phase==0){worker.remove(task);task.cancelQueuedLocked();}
            maybeDrainLocked();
        }
    }
    /** No accepted mutation/notice remains. Queue the engine cleanup before shutting down the mutation worker. */
    private void maybeDrainLocked(){
        if(!closed || pendingMutations!=0 || drainScheduled)return;
        drainScheduled=true;notifyAll();deliveries.shutdown();cleanupState=CleanupState.QUEUED;
        worker.execute(this::cleanupOnWorker);worker.shutdown();
        drainSignal.execute(()->{
            synchronized(this){drainActive=true;drainStartedNanos=System.nanoTime();}
            try{drained.complete(null);}finally{synchronized(this){drainActive=false;drainNotificationFinished=true;notifyAll();}}
        });
        drainSignal.shutdown();
    }
    private void cleanupOnWorker(){
        synchronized(this){cleanupState=CleanupState.RUNNING;}
        boolean success=false;
        try{cleanup.run();success=true;}
        catch(RuntimeException | LinkageError | AssertionError failure){
            rememberFailure("cleanup",failure);
            try{cleanupFailureObserver.accept(failure);}
            catch(RuntimeException | LinkageError | AssertionError observerFailure){recordObserverFailure(observerFailure);}
        }
        finally{synchronized(this){cleanupState=success?CleanupState.SUCCEEDED:CleanupState.FAILED;notifyAll();}}
    }
    private synchronized void rememberFailure(String operation,Throwable failure){lastEngineFailure=operation+": "+failure.getClass().getName();}
    private synchronized void recordObserverFailure(Throwable failure){observerFailures++;rememberFailure("observer",failure);}
    private abstract class Work implements Runnable {
        // Host monitor protects queued/running/delivery-queued/delivering/cancel-notification-queued states.
        int phase;long deliveryStartedNanos;
        final boolean enter(){synchronized(MutationLane.this){if(phase!=0)return false;phase=1;return true;}}
        abstract void cancelQueuedLocked();
    }
    private final class EngineWork extends Work {
        final Runnable command;final EngineTaskObserver observer;
        EngineWork(Runnable command,EngineTaskObserver observer){this.command=command;this.observer=observer;}
        public void run(){
            if(!enter())return;
            try{command.run();}
            catch(RuntimeException | LinkageError | AssertionError failure){
                synchronized(MutationLane.this){failedEngineTasks++;rememberFailure("internal task",failure);}
                report(EngineTaskEvent.FAILED,failure);
            }finally{finished();}
        }
        void report(EngineTaskEvent event,Throwable failure){
            try{observer.report(command,event,failure);}
            catch(RuntimeException | LinkageError | AssertionError observerFailure){recordObserverFailure(observerFailure);}
        }
        void cancelQueuedLocked(){
            phase=4;cancelledEngineTasks++;
            // This replaces the removed queue entry. Observers never run inline on the close/platform caller.
            worker.execute(()->{try{report(EngineTaskEvent.CANCELLED,null);}finally{finished();}});
        }
        void finished(){synchronized(MutationLane.this){active.remove(this);pendingMutations--;maybeDrainLocked();}}
    }
    private final class ApiWork<T> extends Work {
        final Supplier<Outcome<T>> operation;final CompletableFuture<Outcome<T>> result=new CompletableFuture<>();
        ApiWork(Supplier<Outcome<T>> operation){this.operation=operation;}
        public void run(){
            if(!enter())return;
            Outcome<T> outcome;
            try{outcome=Objects.requireNonNull(operation.get());}
            catch(RuntimeException | LinkageError | AssertionError error){outcome=Outcome.rejected(Outcome.Status.FAILED,"mutation-failure",error.getClass().getSimpleName());}
            synchronized(MutationLane.this){completeMutationLocked(outcome);}
        }
        void cancelQueuedLocked(){completeMutationLocked(Outcome.rejected(Outcome.Status.CANCELLED,"shutdown","Queued mutation cancelled before execution"));}
        void completeMutationLocked(Outcome<T> outcome){
            if(phase!=0 && phase!=1)throw new IllegalStateException("Mutation already finished");
            phase=2;pendingMutations--;deliveries.execute(()->deliver(outcome));maybeDrainLocked();
        }
        void deliver(Outcome<T> outcome){
            synchronized(MutationLane.this){phase=3;deliveryStartedNanos=System.nanoTime();}
            try{result.complete(outcome);}
            finally{synchronized(MutationLane.this){if(System.nanoTime()-deliveryStartedNanos>=deliveryBudgetNanos)slowDeliveries++;active.remove(this);MutationLane.this.notifyAll();}}
        }
    }
}
