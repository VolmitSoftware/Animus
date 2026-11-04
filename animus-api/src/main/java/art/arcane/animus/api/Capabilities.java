package art.arcane.animus.api;

import java.util.*;
import java.util.concurrent.CompletionStage;

public final class Capabilities {
    private Capabilities() {}
    public enum Kind { ACTION, CONDITION, TRIGGER }
    public record Key(String namespace,String name) {
        public Key {ApiText.id(namespace);ApiText.id(name);}
        @Override public String toString(){return namespace+":"+name;}
    }
    public record Descriptor(Key key,Kind kind,String description,String configurationSchemaJson,Set<Platform> platforms,boolean privileged) {
        public Descriptor {Objects.requireNonNull(key);Objects.requireNonNull(kind);ApiText.text(description,2048,"description");ApiText.text(configurationSchemaJson,65536,"schema JSON");platforms=Set.copyOf(platforms);if(platforms.isEmpty())throw new IllegalArgumentException("No supported platforms");}
    }
    public record Availability(Descriptor descriptor,boolean available,String reason) {
        public Availability {Objects.requireNonNull(descriptor);ApiText.text(reason,2048,"reason");}
    }
    /** {@code behavior} names the accepted JSON behavior making this call; it is empty for direct dispatch. */
    public record Invocation(NpcHandle npc,Optional<UUID> player,String argumentsJson,boolean preview,Cancellation cancellation,Optional<String> behavior) {
        public Invocation {Objects.requireNonNull(npc);player=Objects.requireNonNull(player);ApiText.text(argumentsJson,65536,"arguments JSON");Objects.requireNonNull(cancellation);behavior=Objects.requireNonNull(behavior);}
    }
    @FunctionalInterface public interface Cancellation {boolean cancelled();}
    @FunctionalInterface public interface Action {CompletionStage<String> run(Invocation invocation) throws Exception;}
    @FunctionalInterface public interface Condition {CompletionStage<Boolean> test(Invocation invocation) throws Exception;}
    public interface Registrar {
        void action(Descriptor descriptor,Action action);
        void condition(Descriptor descriptor,Condition condition);
        void trigger(Descriptor descriptor);
    }
}
