package art.arcane.animus.api;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Bounds text only. JSON syntax/schema validation is the engine's private responsibility. */
final class ApiText {
    private ApiText() {}
    static String text(String value,int maximumBytes,String field){
        Objects.requireNonNull(value,field);
        if(value.isBlank() || value.length()>maximumBytes || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.getBytes(StandardCharsets.UTF_8).length>maximumBytes)throw new IllegalArgumentException(field+" must be nonblank valid UTF-8 within "+maximumBytes+" bytes");
        return value;
    }
    static String id(String value){if(!Objects.requireNonNull(value).matches("[a-z0-9][a-z0-9_-]{0,63}"))throw new IllegalArgumentException("Invalid identifier");return value;}
}
