package art.arcane.animus.core.command;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.builtin.BuiltinCapabilities;
import art.arcane.animus.core.building.BuildCommands;
import art.arcane.animus.core.editor.EditorCommands;
import art.arcane.animus.core.extension.ExtensionRuntime;
import art.arcane.animus.core.gui.HoloService;
import art.arcane.animus.core.quest.QuestService;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.shop.ShopService;
import art.arcane.animus.core.toolkit.Toolkit;
import art.arcane.animus.core.toolkit.ToolkitMode;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What the {@code /animus} commands reach on a platform. {@code extensions} and {@code builtins} may be null while they
 * are not initialized; {@code behaviors} may answer null for the same reason. {@code players} names the online players
 * for completion. {@code plugins} answers whether a server plugin runs, by name, for the {@link NeedsPlugin} commands.
 */
public record CommandServices(AnimusRuntime runtime, Executor files, NpcRuntime<?, ?> living, Supplier<BehaviorCommands> behaviors,
                              ExtensionRuntime extensions, BuiltinCapabilities builtins, CombatControl combat, ShopService<?, ?> shops,
                              HoloService<?, ?> guis, QuestService<?, ?> quests, BuildCommands builds, EditorCommands editor, DebugCommands debug, ProofControl proof,
                              RecordingControl recordings, Supplier<Collection<String>> players, BooleanSupplier stopping,
                              Predicate<String> plugins) {
    public CommandServices {
        Objects.requireNonNull(runtime); Objects.requireNonNull(files); Objects.requireNonNull(living); Objects.requireNonNull(behaviors);
        Objects.requireNonNull(combat); Objects.requireNonNull(shops); Objects.requireNonNull(guis); Objects.requireNonNull(quests); Objects.requireNonNull(builds);
        Objects.requireNonNull(editor); Objects.requireNonNull(debug); Objects.requireNonNull(proof); Objects.requireNonNull(recordings); Objects.requireNonNull(players);
        Objects.requireNonNull(stopping); Objects.requireNonNull(plugins);
    }

    /** The player's menu mode, for replies and help that differ by mode; Simple where the toolkit does not run. */
    public ToolkitMode mode(UUID player) {
        return Toolkit.of(shops.screens()).map((Toolkit<?, ?> toolkit) -> toolkit.mode(player)).orElse(ToolkitMode.SIMPLE);
    }
}
