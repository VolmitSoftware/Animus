package art.arcane.animus.core.platform;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Permission nodes on mod servers. {@code animus.status} is open to everyone. Every other node is granted to
 * operators, or by a permission mod through the vanilla permission atom whose identifier is the node with its first
 * dot as the namespace separator: {@code animus.ride.town} is {@code animus:ride.town}.
 */
public final class ModPermissions {
    public static final String STATUS = "animus.status";
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+"), PATH = Pattern.compile("[a-z0-9_./-]+");

    private ModPermissions() {}

    public static boolean everyone(String node) { return STATUS.equals(node); }

    /** The permission atom identifier for a node, or empty when the node cannot name one. */
    public static Optional<String> atom(String node) {
        if (node == null) return Optional.empty();
        int dot = node.indexOf('.');
        if (dot <= 0 || dot == node.length() - 1) return Optional.empty();
        String namespace = node.substring(0, dot), path = node.substring(dot + 1);
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) return Optional.empty();
        return Optional.of(namespace + ":" + path);
    }
}
