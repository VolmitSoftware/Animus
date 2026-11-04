package art.arcane.animus.bukkit.navigation;

import art.arcane.animus.core.navigation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;

/** Pure coordinator: the owner calls pump; the worker receives no Sampler or live world object. */
final class NavigationWorkQueue implements AutoCloseable {
    interface Sampler {
        boolean worldAvailable(UUID world);
        boolean chunkAvailable(UUID world, int chunkX, int chunkZ);
        VoxelSnapshot.Cell sample(UUID world, int x, int y, int z);
    }
    @FunctionalInterface
    interface Planner {
        PathPlanner.Result plan(VoxelSnapshot snapshot, BlockPos start, BlockPos goal,
                                MovementProfile profile, PathPlanner.Limits limits, BooleanSupplier cancelled);
    }
    private enum Phase { START, CAPTURE, VERIFY, PLANNING }
    private record Chunk(int x, int z) {}
    private final NavigationLimits limits;
    private final LongSupplier clock;
    private final Planner planner;
    private final ThreadPoolExecutor executor;
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Deque<Job> captures = new ArrayDeque<>();
    private boolean closed;

    NavigationWorkQueue(NavigationLimits limits) { this(limits, System::nanoTime); }
    NavigationWorkQueue(NavigationLimits limits, LongSupplier clock) {
        this(limits, clock, new PathPlanner()::plan);
    }
    NavigationWorkQueue(NavigationLimits limits, LongSupplier clock, Planner planner) {
        this.limits = Objects.requireNonNull(limits, "limits"); this.clock = Objects.requireNonNull(clock, "clock");
        this.planner = Objects.requireNonNull(planner, "planner");
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(limits.maximumJobs()), task -> {
            Thread worker = new Thread(task, "Animus-navigation"); worker.setDaemon(true); return worker;
        });
    }

    synchronized NavigationPlan plan(UUID world, CaptureBounds bounds, BlockPos start, BlockPos goal,
                                     MovementProfile profile, PathPlanner.Limits searchLimits) {
        if (closed) throw new IllegalStateException("Navigation bridge is closed");
        Objects.requireNonNull(world, "world"); Objects.requireNonNull(bounds, "bounds"); Objects.requireNonNull(start, "start");
        Objects.requireNonNull(goal, "goal"); Objects.requireNonNull(profile, "profile"); Objects.requireNonNull(searchLimits, "searchLimits");
        if (bounds.voxelCount() > limits.maximumCaptureVoxels()) throw new IllegalArgumentException("Capture exceeds configured voxel budget");
        if (jobs.size() >= limits.maximumJobs()) throw new RejectedExecutionException("Navigation job limit reached");
        Job job = new Job(world, bounds, start, goal, profile, searchLimits);
        jobs.put(job.handle.id(), job); captures.addLast(job);
        return job.handle;
    }
    synchronized int outstandingJobs() { return jobs.size(); }

    /** One work unit is a world check, a voxel/chunk check, or a final chunk verification. */
    int pump(Sampler sampler, long tick) {
        Objects.requireNonNull(sampler, "sampler");
        int work = 0;
        while (work < limits.captureWorkPerTick()) {
            Runnable completion = null;
            synchronized (this) {
                if (closed) break;
                Job job = captures.pollFirst();
                if (job == null) break;
                if (job.handle.cancelled() || !jobs.containsKey(job.handle.id())) { jobs.remove(job.handle.id()); continue; }
                try {
                    completion = captureOne(job, sampler, tick);
                } catch (RuntimeException failure) {
                    jobs.remove(job.handle.id());
                    completion = () -> job.handle.fail(failure);
                }
                work++;
                if (jobs.containsKey(job.handle.id()) && job.phase != Phase.PLANNING && !job.handle.cancelled()) captures.addLast(job);
            }
            if (completion != null) completion.run();
        }
        return work;
    }

    private Runnable captureOne(Job job, Sampler sampler, long tick) {
        if (job.phase == Phase.START) {
            job.firstTick = tick; job.firstNanos = clock.getAsLong();
            if (!sampler.worldAvailable(job.world)) {
                jobs.remove(job.handle.id());
                return () -> job.handle.complete(new PathPlanner.Result(PathPlanner.Status.UNAVAILABLE_TERRAIN, List.of(), 0, 0, true));
            }
            job.phase = Phase.CAPTURE; return null;
        }
        if (job.phase == Phase.CAPTURE) {
            int x = job.bounds.minX() + job.offset % job.bounds.sizeX();
            int z = job.bounds.minZ() + (job.offset / job.bounds.sizeX()) % job.bounds.sizeZ();
            int y = job.bounds.minY() + job.offset / (job.bounds.sizeX() * job.bounds.sizeZ());
            Chunk chunk = new Chunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            if (!sampler.chunkAvailable(job.world, chunk.x(), chunk.z())) {
                job.missing.add(chunk); job.seen.remove(chunk); job.unavailable++;
            } else {
                VoxelSnapshot.Cell cell = Objects.requireNonNull(sampler.sample(job.world, x, y, z), "sampled cell");
                if (cell != VoxelSnapshot.Cell.UNAVAILABLE) { job.builder.set(x, y, z, cell); job.sampled++; }
                else job.unavailable++;
                if (!job.missing.contains(chunk)) job.seen.add(chunk);
            }
            job.offset++;
            if (job.offset == job.bounds.voxelCount()) {
                job.verify = new ArrayDeque<>(job.seen); job.phase = Phase.VERIFY;
                if (job.verify.isEmpty()) return dispatch(job, tick);
            }
            return null;
        }
        if (job.phase == Phase.VERIFY) {
            Chunk chunk = job.verify.removeFirst();
            if (sampler.chunkAvailable(job.world, chunk.x(), chunk.z())) job.builder.availableChunk(chunk.x(), chunk.z());
            if (job.verify.isEmpty()) return dispatch(job, tick);
        }
        return null;
    }

    private Runnable dispatch(Job job, long tick) {
        VoxelSnapshot snapshot = job.builder.build(); job.builder = null;
        job.handle.captured(new CaptureProvenance(job.world, job.bounds, job.firstTick, tick, job.firstNanos, clock.getAsLong(), job.sampled, job.unavailable));
        job.phase = Phase.PLANNING;
        job.worker = () -> {
            try {
                PathPlanner.Result result = planner.plan(snapshot, job.start, job.goal, job.profile, job.searchLimits,
                        () -> job.handle.cancelled() || Thread.currentThread().isInterrupted());
                job.handle.complete(result);
            } catch (RuntimeException failure) { job.handle.fail(failure); }
            finally { release(job); }
        };
        return () -> {
            try { executor.execute(job.worker); }
            catch (RejectedExecutionException failure) {
                release(job);
                if (job.handle.cancelled() || isClosed()) job.handle.cancelFromQueue();
                else job.handle.fail(failure);
            }
        };
    }

    private void cancel(Job job) {
        job.handle.cancelFromQueue();
        synchronized (this) {
            captures.remove(job);
            if (job.phase != Phase.PLANNING || (job.worker != null && executor.remove(job.worker))) jobs.remove(job.handle.id());
        }
    }
    private synchronized void release(Job job) { jobs.remove(job.handle.id()); }
    private synchronized boolean isClosed() { return closed; }
    boolean awaitTermination(long duration, TimeUnit unit) throws InterruptedException { return executor.awaitTermination(duration, unit); }

    @Override public void close() {
        List<Job> closing;
        synchronized (this) {
            if (closed) return;
            closed = true; closing = List.copyOf(jobs.values()); jobs.clear(); captures.clear();
        }
        closing.forEach(job -> job.handle.cancelFromQueue());
        executor.shutdownNow();
    }

    private final class Job {
        final UUID world;
        final CaptureBounds bounds;
        final BlockPos start, goal;
        final MovementProfile profile;
        final PathPlanner.Limits searchLimits;
        final NavigationPlan handle;
        final Set<Chunk> seen = new LinkedHashSet<>(), missing = new HashSet<>();
        VoxelSnapshot.Builder builder;
        Phase phase = Phase.START;
        Deque<Chunk> verify;
        Runnable worker;
        int offset, sampled, unavailable;
        long firstTick, firstNanos;
        Job(UUID world, CaptureBounds bounds, BlockPos start, BlockPos goal, MovementProfile profile, PathPlanner.Limits searchLimits) {
            this.world = world; this.bounds = bounds; this.start = start; this.goal = goal;
            this.profile = profile; this.searchLimits = searchLimits;
            builder = new VoxelSnapshot.Builder(world.toString(), bounds.minX(), bounds.minY(), bounds.minZ(), bounds.sizeX(), bounds.sizeY(), bounds.sizeZ());
            handle = new NavigationPlan(() -> cancel(this));
        }
    }
}
