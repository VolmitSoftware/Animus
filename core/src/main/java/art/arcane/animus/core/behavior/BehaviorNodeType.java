package art.arcane.animus.core.behavior;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Node words of the behavior language and the fields each accepts. The compiler and the generated schema share this list. */
public enum BehaviorNodeType {
    SEQUENCE("sequence", "Runs its steps in order.", "steps"),
    ACTION("action", "Runs a capability and waits for its result.", "capability", "arguments", "bindings"),
    CONDITION("condition", "Tests a capability, then runs then or else.", "capability", "arguments", "bindings", "then", "else"),
    WAIT("wait", "Waits a number of behavior ticks.", "ticks"),
    REPEAT("repeat", "Runs its body a fixed number of times.", "count", "body"),
    PARALLEL("parallel", "Runs independent branches, then joins them.", "branches"),
    CALL("call", "Runs another routine of this document.", "routine"),
    SET("set", "Writes a declared variable.", "variable", "value");

    private final String jsonName;
    private final String description;
    private final List<String> fields;
    BehaviorNodeType(String jsonName, String description, String... fields) {
        this.jsonName = jsonName;
        this.description = description;
        this.fields = List.of(fields);
    }
    public String jsonName() { return jsonName; }
    public String description() { return description; }
    /** Fields other than {@code type}. */
    public List<String> fields() { return fields; }
    /** Every accepted key, starting with {@code type}. */
    public List<String> keys() {
        List<String> keys = new ArrayList<>(fields.size() + 1);
        keys.add("type");
        keys.addAll(fields);
        return List.copyOf(keys);
    }
    public static Optional<BehaviorNodeType> find(String name) {
        for (BehaviorNodeType type : values()) if (type.jsonName.equals(name)) return Optional.of(type);
        return Optional.empty();
    }
    public static List<String> names() {
        List<String> names = new ArrayList<>();
        for (BehaviorNodeType type : values()) names.add(type.jsonName);
        return List.copyOf(names);
    }
}
