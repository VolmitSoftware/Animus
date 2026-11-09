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
    /**
     * {@code behavior} names the accepted JSON behavior making this call; it is empty for direct dispatch.
     *
     * @param npc NPC the call applies to
     * @param player player the call applies to, if any
     * @param argumentsJson capability arguments as a JSON object
     * @param preview true when the call only previews its effect
     * @param cancellation signal that stops the call early
     * @param behavior behavior that requested the call
     */
    public record Invocation(NpcHandle npc,Optional<UUID> player,String argumentsJson,boolean preview,Cancellation cancellation,Optional<String> behavior) {
        public Invocation {Objects.requireNonNull(npc);player=Objects.requireNonNull(player);ApiText.text(argumentsJson,65536,"arguments JSON");Objects.requireNonNull(cancellation);behavior=Objects.requireNonNull(behavior);}
    }
    @FunctionalInterface public interface Cancellation {boolean cancelled();}
    @FunctionalInterface public interface Action {CompletionStage<String> run(Invocation invocation) throws Exception;}
    @FunctionalInterface public interface Condition {CompletionStage<Boolean> test(Invocation invocation) throws Exception;}
    /**
     * Fires a trigger this module registered. Thread-safe and nonblocking: the result says whether the
     * event was queued. OK(false) means the NPC is dormant or has no bound triggers. Overflow returns BUSY
     * and is dropped. Other statuses carry a diagnostic. Publishing never grants world authority.
     */
    @FunctionalInterface public interface TriggerPublisher {Outcome<Boolean> publish(Key trigger,NpcHandle npc,Optional<UUID> player);}
    public interface Registrar {
        void action(Descriptor descriptor,Action action);
        void condition(Descriptor descriptor,Condition condition);
        void trigger(Descriptor descriptor);
    }
}
