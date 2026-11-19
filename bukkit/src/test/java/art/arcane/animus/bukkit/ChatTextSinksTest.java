package art.arcane.animus.bukkit;

import art.arcane.animus.core.text.ChatLine;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CraftBukkit and BungeeCord read a dotted word in legacy text, such as {@code config.toml}, as a web link. Every class
 * but {@link BukkitChat} hands chat text to the server as components; only the console, which shows no links, gets a String.
 */
class ChatTextSinksTest {
    private static final String CHAT = "art/arcane/animus/bukkit/BukkitChat";
    private static final String VOLMLIB_PLUGIN = "art/arcane/volmlib/util/plugin/";
    private static final Set<String> LEGACY_PARSERS = Set.of("fromLegacyText", "fromLegacy");
    private static final Set<String> BOOK_PAGES = Set.of("setPages", "setPage", "addPage");

    @Test void chatTextReachesPlayersOnlyAsComponents() throws IOException, URISyntaxException {
        assertEquals(Set.of(), calls(BukkitActor.class, (String owner, InvokeInstruction invoke) -> !owner.equals(CHAT) && linking(invoke)));
    }

    /**
     * The Bukkit jar leaves out VolmLib's ComponentText and Adventure's legacy and plain serializers, so chat and the
     * splash reach VolmLib only through the Adventure component overloads.
     */
    @Test void noAnimusClassReachesComponentText() throws IOException, URISyntaxException {
        BiPredicate<String, InvokeInstruction> reaches = (String owner, InvokeInstruction invoke) -> componentText(invoke);
        assertEquals(Set.of(), calls(BukkitActor.class, reaches));
        assertEquals(Set.of(), calls(ChatLine.class, reaches));
    }

    private static boolean componentText(InvokeInstruction invoke) {
        String owner = invoke.owner().asInternalName(), name = invoke.name().stringValue(), type = invoke.typeSymbol().descriptorString();
        if (owner.equals(VOLMLIB_PLUGIN + "ComponentText") || type.contains(VOLMLIB_PLUGIN + "ComponentText;")) return true;
        if (owner.startsWith("net/kyori/adventure/text/serializer/legacy/") || owner.startsWith("net/kyori/adventure/text/serializer/plain/")) return true;
        if (owner.endsWith("/director/help/DirectorMiniMenu") && name.startsWith("deliver")) return true;
        return (owner.equals(VOLMLIB_PLUGIN + "ComponentMessenger") || owner.equals(VOLMLIB_PLUGIN + "ComponentLog"))
                && !type.contains("Lnet/kyori/adventure/text/Component;");
    }

    /** Every call in the classes beside {@code anchor}, a directory or a jar, that {@code flagged} picks out. */
    private static Set<String> calls(Class<?> anchor, BiPredicate<String, InvokeInstruction> flagged) throws IOException, URISyntaxException {
        Path location = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (FileSystem jar = Files.isDirectory(location) ? null : FileSystems.newFileSystem(location)) {
            Path classes = jar == null ? location : jar.getPath("/");
            Set<String> found = new TreeSet<>();
            List<Path> files;
            try (Stream<Path> walk = Files.walk(classes)) { files = walk.filter((Path file) -> file.toString().endsWith(".class")).toList(); }
            for (Path file : files) {
                ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
                String owner = model.thisClass().asInternalName();
                for (MethodModel method : model.methods()) {
                    if (method.code().isEmpty()) continue;
                    for (CodeElement element : method.code().get()) {
                        if (element instanceof InvokeInstruction invoke && flagged.test(owner, invoke))
                            found.add(owner + "." + method.methodName().stringValue() + " calls " + invoke.owner().asInternalName() + "." + invoke.name().stringValue());
                    }
                }
            }
            assertTrue(files.size() > 100, "no plugin classes under " + location);
            return found;
        }
    }

    private static boolean linking(InvokeInstruction invoke) {
        String owner = invoke.owner().asInternalName(), name = invoke.name().stringValue(), type = invoke.typeSymbol().descriptorString();
        if (owner.equals("net/md_5/bungee/api/chat/TextComponent") && LEGACY_PARSERS.contains(name)) return true;
        if (owner.equals("org/bukkit/inventory/meta/BookMeta") && BOOK_PAGES.contains(name)) return true;
        if (!owner.startsWith("org/bukkit/") || owner.equals("org/bukkit/command/ConsoleCommandSender")) return false;
        if (name.equals("broadcastMessage")) return true;
        return (name.equals("sendMessage") || name.equals("sendRawMessage")) && type.contains("Ljava/lang/String;");
    }
}
