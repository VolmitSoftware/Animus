package art.arcane.animus.api;

import java.util.*;

/**
 * Module converters for another plugin's saved NPC data. The engine reads one file from {@code imports/inbox/},
 * asks registered converters to detect it, and writes the chosen converter's records into the same reviewed import
 * preview that built-in converters produce. Administrators then apply or stage it with the normal import commands.
 * Both calls run on the bounded module worker with the module invocation deadline and must not wait on
 * {@link DefinitionService} results. @since 1.1
 */
public final class ImportConverters {
    private ImportConverters() {}
    /**
     * {@code key.namespace()} must be the module id; imported NPC ids become {@code namespace-name-sourceId}.
     *
     * @param key converter key; its namespace is the module id
     * @param description short description of what the converter reads
     * @param sourcePlugin name of the plugin whose data is converted
     * @param platforms platforms the converter runs on
     */
    public record Descriptor(Capabilities.Key key,String description,String sourcePlugin,Set<Platform> platforms) {
        public Descriptor {Objects.requireNonNull(key);ApiText.text(description,2048,"description");ApiText.text(sourcePlugin,128,"source plugin");platforms=Set.copyOf(platforms);if(platforms.isEmpty())throw new IllegalArgumentException("No supported platforms");}
    }
    /**
     * One inbox file: its plain name and UTF-8 text, at most 16 MiB.
     *
     * @param fileName plain file name
     * @param text UTF-8 file text
     */
    public record Source(String fileName,String text) {
        public Source {ApiText.text(fileName,255,"file name");Objects.requireNonNull(text);if(text.length()>16*1024*1024)throw new IllegalArgumentException("Source exceeds 16 MiB");}
    }
    public record Detection(boolean recognized,String reason) {
        public Detection {ApiText.text(reason,1024,"reason");}
    }
    /**
     * One converted NPC. {@code definitionJson} is an Animus NPC definition object. The engine sets id, revision,
     * dormant spawn state and migration provenance, keeps a valid {@code uuid} or derives a stable one, and disables
     * imported combat and patrols until reviewed. {@code sourceId} must be unique and stable for the source NPC.
     *
     * @param sourceId stable id of the source NPC
     * @param definitionJson Animus NPC definition as a JSON object
     * @param warnings conversion warnings for review
     */
    public record Record(int sourceId,String definitionJson,List<String> warnings) {
        public Record {if(sourceId<0)throw new IllegalArgumentException("Negative source id");ApiText.text(definitionJson,1024*1024,"definition JSON");warnings=List.copyOf(warnings);if(warnings.size()>64)throw new IllegalArgumentException("Too many warnings");warnings.forEach(w->ApiText.text(w,1024,"warning"));}
    }
    public record Conversion(List<Record> records,List<String> warnings) {
        public Conversion {records=List.copyOf(records);warnings=List.copyOf(warnings);if(records.isEmpty()||records.size()>10_000||warnings.size()>256)throw new IllegalArgumentException("Conversion needs 1..10000 records and at most 256 warnings");warnings.forEach(w->ApiText.text(w,1024,"warning"));}
    }
    public interface Converter {
        Detection detect(Source source,Capabilities.Cancellation cancellation) throws Exception;
        Conversion convert(Source source,Capabilities.Cancellation cancellation) throws Exception;
    }
    public interface Registrar {void converter(Descriptor descriptor,Converter converter);}
}
