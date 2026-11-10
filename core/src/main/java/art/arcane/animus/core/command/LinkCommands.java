package art.arcane.animus.core.command;

import art.arcane.animus.core.migration.LinkRemap;
import art.arcane.animus.core.migration.NpcLinks;
import art.arcane.volmlib.util.director.annotations.Director;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Points each quest plugin's files at the Animus NPCs a Citizens import created: {@code /animus import <plugin>}
 * previews, applies and rolls back, one plugin at a time, and shows only while that plugin runs.
 */
final class LinkCommands {
    private LinkCommands() {}

    /** Runs one relink step for a plugin on the file lane. */
    static void run(CommandGroup group, LinkRemap.Plugin plugin, Step step) {
        group.files(CommandGroup.actor(), () -> {
            Path plugins = group.runtime().importHost().pluginsDirectory().orElseThrow(() -> new IOException("This server has no plugins folder to relink"));
            return step.run(new LinkRemap(group.runtime().documents(), plugins, plugin, NpcLinks.from(group.runtime().npcs().all())));
        });
    }

    @FunctionalInterface interface Step { List<String> run(LinkRemap remap) throws IOException; }

    @Director(name = "quests", description = "Point Quests files at the NPCs a Citizens import created")
    @NeedsPlugin("Quests")
    static final class Quests extends CommandGroup {
        Quests(CommandServices services) { super(services); }

        @Director(description = "List the Citizens NPC references that would point at imported NPCs")
        void preview() { run(this, LinkRemap.Plugin.QUESTS, LinkRemap::preview); }

        @Director(description = "Point the references at imported NPCs, keeping the originals")
        void apply() { run(this, LinkRemap.Plugin.QUESTS, LinkRemap::apply); }

        @Director(description = "Put back every file nobody edited since the link")
        void rollback() { run(this, LinkRemap.Plugin.QUESTS, LinkRemap::rollback); }
    }

    @Director(name = "beautyquests", description = "Point BeautyQuests files at the NPCs a Citizens import created")
    @NeedsPlugin("BeautyQuests")
    static final class BeautyQuests extends CommandGroup {
        BeautyQuests(CommandServices services) { super(services); }

        @Director(description = "List the Citizens NPC references that would point at imported NPCs")
        void preview() { run(this, LinkRemap.Plugin.BEAUTYQUESTS, LinkRemap::preview); }

        @Director(description = "Point the references at imported NPCs, keeping the originals")
        void apply() { run(this, LinkRemap.Plugin.BEAUTYQUESTS, LinkRemap::apply); }

        @Director(description = "Put back every file nobody edited since the link")
        void rollback() { run(this, LinkRemap.Plugin.BEAUTYQUESTS, LinkRemap::rollback); }
    }

    @Director(name = "betonquest", description = "Point BetonQuest packages at the NPCs a Citizens import created")
    @NeedsPlugin("BetonQuest")
    static final class BetonQuest extends CommandGroup {
        BetonQuest(CommandServices services) { super(services); }

        @Director(description = "List the Citizens NPC references that would point at imported NPCs")
        void preview() { run(this, LinkRemap.Plugin.BETONQUEST, LinkRemap::preview); }

        @Director(description = "Point the references at imported NPCs, keeping the originals")
        void apply() { run(this, LinkRemap.Plugin.BETONQUEST, LinkRemap::apply); }

        @Director(description = "Put the original Citizens references back")
        void rollback() { run(this, LinkRemap.Plugin.BETONQUEST, LinkRemap::rollback); }
    }

    @Director(name = "typewriter", description = "Point Typewriter pages at the NPCs a Citizens import created")
    @NeedsPlugin("Typewriter")
    static final class Typewriter extends CommandGroup {
        Typewriter(CommandServices services) { super(services); }

        @Director(description = "List the Citizens NPC references that would point at imported NPCs")
        void preview() { run(this, LinkRemap.Plugin.TYPEWRITER, LinkRemap::preview); }

        @Director(description = "Point the references at imported NPCs, keeping the originals")
        void apply() { run(this, LinkRemap.Plugin.TYPEWRITER, LinkRemap::apply); }

        @Director(description = "Put the original Citizens references back")
        void rollback() { run(this, LinkRemap.Plugin.TYPEWRITER, LinkRemap::rollback); }
    }
}
