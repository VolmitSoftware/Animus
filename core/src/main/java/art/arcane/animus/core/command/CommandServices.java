package art.arcane.animus.core.command;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.builtin.BuiltinCapabilities;
import art.arcane.animus.core.building.BuildCommands;
import art.arcane.animus.core.editor.EditorCommands;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.gui.HoloService;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.shop.ShopService;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * What the {@code /animus} commands reach on a platform. {@code extensions} and {@code builtins} may be null while they
 * are not initialized; {@code behaviors} may answer null for the same reason. {@code players} names the online players
 * for completion.
 */
public record CommandServices(AnimusRuntime runtime, Executor files, NpcRuntime<?, ?> living, Supplier<BehaviorCommands> behaviors,
                              ExtensionRuntime extensions, BuiltinCapabilities builtins, CombatControl combat, ShopService<?, ?> shops,
                              HoloService<?, ?> guis, BuildCommands builds, EditorCommands editor, DebugCommands debug, ProofControl proof,
                              RecordingControl recordings, Supplier<Collection<String>> players, BooleanSupplier stopping) {
    public CommandServices {
        Objects.requireNonNull(runtime); Objects.requireNonNull(files); Objects.requireNonNull(living); Objects.requireNonNull(behaviors);
        Objects.requireNonNull(combat); Objects.requireNonNull(shops); Objects.requireNonNull(guis); Objects.requireNonNull(builds);
        Objects.requireNonNull(editor); Objects.requireNonNull(debug); Objects.requireNonNull(proof); Objects.requireNonNull(recordings); Objects.requireNonNull(players);
        Objects.requireNonNull(stopping);
    }
}
