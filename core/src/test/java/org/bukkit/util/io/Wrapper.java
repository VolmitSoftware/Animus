package org.bukkit.util.io;

import java.io.Serializable;
import java.util.Map;

/** Test stand-in with the serialized shape of Bukkit's BukkitObjectOutputStream wrapper. */
public final class Wrapper<T extends Map<String, ?> & Serializable> implements Serializable {
    private static final long serialVersionUID = -986209235411767547L;
    public final T map;

    public Wrapper(T map) { this.map = map; }
}
