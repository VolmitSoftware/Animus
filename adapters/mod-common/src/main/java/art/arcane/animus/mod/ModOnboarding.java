package art.arcane.animus.mod;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.command.AnimusDirector;
import art.arcane.animus.core.onboarding.Onboarding;
import art.arcane.animus.core.text.ChatLine;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * First-run guidance on Fabric, NeoForge and Forge, the same as on Bukkit: an admin who joins gets the join hint
 * {@link Onboarding#JOIN_DELAY_TICKS} later, and at startup the migration notice goes to the log and to every online
 * admin. Lines are sent on the server thread; file reads run on the file lane.
 */
final class ModOnboarding {
    private final MinecraftServer server;
    private final ModTicks ticks;
    private final AnimusRuntime runtime;
    private final Onboarding onboarding;
    private final Executor files;
    private final BooleanSupplier stopping;
    private final Consumer<String> log, warn;
    private final Delays delays = new Delays();

    ModOnboarding(MinecraftServer server, ModTicks ticks, AnimusRuntime runtime, Executor files, BooleanSupplier stopping, Consumer<String> log, Consumer<String> warn) {
        this.server = server;
        this.ticks = ticks;
        this.runtime = runtime;
        this.files = files;
        this.stopping = stopping;
        this.log = log;
        this.warn = warn;
        onboarding = Onboarding.of(runtime);
        ticks.every(delays::tick);
        announce();
    }

    /** Server thread. */
    void joined(ServerPlayer player) {
        if (!admin(player)) return;
        UUID id = player.getUUID();
        work("Join hint", () -> {
            Optional<ChatLine> hint = onboarding.joinHint(id);
            hint.ifPresent((ChatLine line) -> ticks.later(() -> delays.add(() -> send(id, line), Onboarding.JOIN_DELAY_TICKS)));
        });
    }

    private void announce() {
        work("Citizens migration check", () -> {
            Optional<String> notice = onboarding.startupNotice();
            if (notice.isEmpty()) return;
            log.accept(notice.get());
            ChatLine line = Onboarding.adminNotice(notice.get());
            ticks.later(() -> { for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) send(player.getUUID(), line); });
        });
    }

    private void send(UUID id, ChatLine line) {
        if (stopping.getAsBoolean()) return;
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null && admin(player)) player.sendSystemMessage(ModText.adventure(line.component()));
    }

    private static boolean admin(ServerPlayer player) { return VanillaRuntimeHost.permitted(player.permissions(), AnimusDirector.ADMIN); }

    @FunctionalInterface private interface FileWork { void run() throws IOException; }

    private void work(String label, FileWork work) {
        try {
            files.execute(() -> {
                if (stopping.getAsBoolean() || !runtime.isRunning()) return;
                try { work.run(); }
                catch (IOException | RuntimeException failure) { warn.accept(label + " failed: " + failure.getMessage()); }
            });
        } catch (RejectedExecutionException busy) { warn.accept(label + " skipped: file queue full"); }
    }

    /** Work that runs a number of server ticks from now. Server thread only. */
    static final class Delays {
        private static final class Waiting {
            private final Runnable work;
            private long left;

            private Waiting(Runnable work, long left) { this.work = work; this.left = left; }
        }

        private final List<Waiting> waiting = new ArrayList<>();

        void add(Runnable work, long ticks) { waiting.add(new Waiting(work, Math.max(1, ticks))); }

        void tick() {
            List<Runnable> due = new ArrayList<>();
            for (Iterator<Waiting> cursor = waiting.iterator(); cursor.hasNext(); ) {
                Waiting entry = cursor.next();
                if (--entry.left > 0) continue;
                cursor.remove();
                due.add(entry.work);
            }
            due.forEach(Runnable::run);
        }
    }
}
