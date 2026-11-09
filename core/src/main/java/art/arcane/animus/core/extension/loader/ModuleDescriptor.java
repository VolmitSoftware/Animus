package art.arcane.animus.core.extension.loader;

import art.arcane.animus.api.*;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** Generated archive metadata. Runtime manifest must agree exactly with this reviewable declaration. */
public record ModuleDescriptor(ModuleManifest manifest,String main,List<String> exports) {
    public static final String FILE="animus-module.json";
    public ModuleDescriptor {
        Objects.requireNonNull(manifest);exports=List.copyOf(exports);
        if(!binaryName(main) || exports.size()>32 || exports.stream().distinct().count()!=exports.size()
                || exports.stream().anyMatch(p->!binaryName(p)||reserved(p)))throw new IllegalArgumentException("Invalid main/exports");
        if(reserved(main))throw new IllegalArgumentException("Reserved entrypoint package");
    }
    static boolean binaryName(String value){return value!=null && value.length()<=240 && value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");}
    static boolean reserved(String name){
        // Shadow may relocate the private prefix; the public API exclusion keeps this explicit guard stable.
        return name.startsWith("art.arcane.animus.api.")||name.startsWith("art.arcane.animus.")||name.startsWith("java.")||name.startsWith("javax.")||name.startsWith("jdk.")||name.startsWith("sun.");
    }
    boolean exports(String binaryName){return exports.stream().anyMatch(p->binaryName.startsWith(p+"."));}
    static ModuleDescriptor parse(String text)throws IOException {
        try {
            JsonObject root=JsonDocuments.object(text);keys(root,"schemaVersion","id","version","api","platforms","dependencies","main","exports");
            if(integer(root,"schemaVersion")!=1)throw new IllegalArgumentException("Unsupported schemaVersion");
            JsonObject api=object(root,"api");
            if(api.has("minimumMinor"))keys(api,"minimumMajor","maximumMajor","minimumMinor");else keys(api,"minimumMajor","maximumMajor");
            Set<Platform> platforms=new LinkedHashSet<>();
            for(JsonElement entry:array(root,"platforms",6))if(!platforms.add(Platform.valueOf(string(entry))))throw new IllegalArgumentException("Duplicate platform");
            List<ModuleManifest.Dependency> dependencies=new ArrayList<>();
            for(JsonElement element:array(root,"dependencies",32)) {
                if(!element.isJsonObject())throw new IllegalArgumentException("Dependency must be an object");
                JsonObject dep=element.getAsJsonObject();keys(dep,"id","minimumInclusive","maximumExclusive");
                dependencies.add(new ModuleManifest.Dependency(string(dep,"id"),version(string(dep,"minimumInclusive")),version(string(dep,"maximumExclusive"))));
            }
            List<String> exports=new ArrayList<>();for(JsonElement value:array(root,"exports",32))exports.add(string(value));
            return new ModuleDescriptor(new ModuleManifest(string(root,"id"),version(string(root,"version")),
                    new ApiRange(integer(api,"minimumMajor"),integer(api,"maximumMajor"),api.has("minimumMinor")?integer(api,"minimumMinor"):0),platforms,dependencies),string(root,"main"),exports);
        }catch(IllegalArgumentException|IllegalStateException|NullPointerException error){throw new IOException("Invalid module manifest: "+error.getMessage(),error);}
    }
    private static void keys(JsonObject object,String...allowed){
        Set<String> keys=Set.of(allowed);if(!object.keySet().equals(keys))throw new IllegalArgumentException("Missing or unknown manifest fields");
    }
    private static JsonObject object(JsonObject root,String key){JsonElement value=root.get(key);if(value==null||!value.isJsonObject())throw new IllegalArgumentException("Expected object "+key);return value.getAsJsonObject();}
    private static JsonArray array(JsonObject root,String key,int limit){JsonElement value=root.get(key);if(value==null||!value.isJsonArray()||value.getAsJsonArray().size()>limit)throw new IllegalArgumentException("Invalid/oversized array "+key);return value.getAsJsonArray();}
    private static String string(JsonObject root,String key){return string(root.get(key));}
    private static String string(JsonElement value){if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().length()>256)throw new IllegalArgumentException("Expected bounded string");return value.getAsString();}
    private static int integer(JsonObject root,String key){JsonElement value=root.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!value.toString().matches("0|[1-9][0-9]{0,8}"))throw new IllegalArgumentException("Expected bounded integer "+key);return value.getAsInt();}
    private static Version version(String value){if(!value.matches("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})"))throw new IllegalArgumentException("Expected numeric release version");String[] n=value.split("\\.");return new Version(Integer.parseInt(n[0]),Integer.parseInt(n[1]),Integer.parseInt(n[2]));}
}
