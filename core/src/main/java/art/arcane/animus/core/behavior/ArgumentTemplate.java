package art.arcane.animus.core.behavior;

import art.arcane.animus.core.behavior.BehaviorProgram.Type;
import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Immutable literal chunks and slots. JSON pointers are resolved once, during compilation. A slot is filled at execution
 * from a declared variable (bindings), an expression (expressions) or a template (templates); values are written as
 * canonical JSON, a whole-number expression result as an integer.
 */
public final class ArgumentTemplate {
    public static final int MAX_BYTES=65536,MAX_BINDINGS=32,MAX_SEGMENTS=16,MAX_POINTER_BYTES=4096,MAX_LITERAL_NODES=16384;
    public static final class Invalid extends IllegalArgumentException {
        private final String field;
        Invalid(String field,String message){super(message);this.field=field;}
        public String field(){return field;}
    }
    private sealed interface Part permits Literal,Slot {}
    private record Literal(String json,int bytes)implements Part{}
    private sealed interface Slot extends Part permits Bound,Computed,Text {String field();}
    private record Bound(String variable,Type type,String field)implements Slot{}
    private record Computed(BehaviorExpression expression,String field)implements Slot{}
    private record Text(BehaviorTemplate template,String field)implements Slot{}
    private final List<Part> parts;private final String field;
    private ArgumentTemplate(List<Part> parts,String field){this.parts=List.copyOf(parts);this.field=field;}

    static ArgumentTemplate compile(JsonObject arguments,JsonObject bindings,JsonObject expressions,JsonObject templates,Map<String,Type> variables,String nodePath){
        if(bindings.size()>MAX_BINDINGS)throw new Invalid(nodePath+".bindings","at most 32 argument bindings");
        if(bindings.size()+expressions.size()+templates.size()>MAX_BINDINGS)throw new Invalid(nodePath+(expressions.size()>0?".expressions":".templates"),"at most 32 bindings, expressions and templates together");
        Map<List<String>,Slot> targets=new LinkedHashMap<>();
        for(var entry:bindings.entrySet()){
            String field=nodePath+".bindings["+entry.getKey()+"]";
            List<String> pointer=claim(targets,entry.getKey(),field);
            JsonElement raw=entry.getValue();
            if(!raw.isJsonObject()||raw.getAsJsonObject().size()!=1||!raw.getAsJsonObject().has("variable"))throw new Invalid(field,"expected only a variable reference");
            JsonElement reference=raw.getAsJsonObject().get("variable");
            if(!reference.isJsonPrimitive()||!reference.getAsJsonPrimitive().isString())throw new Invalid(field+".variable","expected declared variable name");
            String name=reference.getAsString();Type declared=variables.get(name);
            if(declared==null)throw new Invalid(field+".variable","unknown declared variable");
            targets.put(pointer,new Bound(name,declared,field));
        }
        for(var entry:expressions.entrySet()){
            String field=nodePath+".expressions["+entry.getKey()+"]";
            List<String> pointer=claim(targets,entry.getKey(),field);
            targets.put(pointer,new Computed(BehaviorExpression.compile(source(entry.getValue(),field),variables.keySet(),field),field));
        }
        for(var entry:templates.entrySet()){
            String field=nodePath+".templates["+entry.getKey()+"]";
            List<String> pointer=claim(targets,entry.getKey(),field);
            targets.put(pointer,new Text(BehaviorTemplate.compile(source(entry.getValue(),field),variables.keySet(),field),field));
        }
        // Resolve only after overlap validation, so either parent/child declaration order has the same result.
        for(var entry:targets.entrySet()){
            JsonElement current=arguments;String field=entry.getValue().field();
            for(String segment:entry.getKey()){
                if(current.isJsonObject()){
                    current=current.getAsJsonObject().get(segment);
                    if(current==null)throw new Invalid(field,"pointer names a missing object property");
                }else if(current.isJsonArray()){
                    if(!segment.matches("0|[1-9][0-9]*")||segment.length()>9)throw new Invalid(field,"expected canonical array index");
                    int index=Integer.parseInt(segment);
                    if(index>=current.getAsJsonArray().size())throw new Invalid(field,"array index outside existing arguments");
                    current=current.getAsJsonArray().get(index);
                }else throw new Invalid(field,"pointer traverses a scalar value");
            }
            if(!current.isJsonNull())throw new Invalid(field,"binding requires an existing null placeholder");
        }
        Builder builder=new Builder(targets,nodePath+".arguments");builder.visit(arguments,List.of(),0);builder.flush();
        return new ArgumentTemplate(builder.parts,nodePath+".arguments");
    }
    private static List<String> claim(Map<List<String>,Slot> targets,String encoded,String field){
        List<String> pointer=pointer(encoded,field);
        for(List<String> previous:targets.keySet())if(prefix(pointer,previous)||prefix(previous,pointer))throw new Invalid(field,"overlapping argument bindings");
        return pointer;
    }
    private static String source(JsonElement raw,String field){
        if(raw==null||!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isString()||raw.getAsString().isBlank())throw new Invalid(field,"expected nonblank string");
        return raw.getAsString();
    }
    private static boolean prefix(List<String> left,List<String> right){return left.size()<=right.size()&&left.equals(right.subList(0,left.size()));}
    private static List<String> pointer(String encoded,String field){
        if(encoded.isEmpty()||encoded.charAt(0)!='/')throw new Invalid(field,"expected a non-root JSON Pointer");
        if(!StandardCharsets.UTF_8.newEncoder().canEncode(encoded)||encoded.getBytes(StandardCharsets.UTF_8).length>MAX_POINTER_BYTES)throw new Invalid(field,"pointer must be valid UTF-8 within 4096 bytes");
        String[] raw=encoded.substring(1).split("/",-1);if(raw.length>MAX_SEGMENTS)throw new Invalid(field,"pointer exceeds 16 segments");
        List<String> result=new ArrayList<>();
        for(String segment:raw){
            StringBuilder decoded=new StringBuilder();
            for(int i=0;i<segment.length();i++){
                char c=segment.charAt(i);
                if(c!='~')decoded.append(c);
                else if(++i<segment.length()&&(segment.charAt(i)=='0'||segment.charAt(i)=='1'))decoded.append(segment.charAt(i)=='0'?'~':'/');
                else throw new Invalid(field,"invalid JSON Pointer escape");
            }
            result.add(decoded.toString());
        }
        return List.copyOf(result);
    }
    /** Every slot filled at execution: bindings, expressions and templates. Zero means the arguments are fully literal. */
    public int bindings(){return (int)parts.stream().filter(Slot.class::isInstance).count();}
    /** Bounded valid UTF-8 object JSON from variables alone; refuses templates that hold expressions or templates. */
    public String resolve(Map<String,Value> variables){return resolve(variables,null);}
    /**
     * Bounded valid UTF-8 object JSON suitable for schema validation before module admission. Expression failures throw
     * {@link BehaviorExpression.Failed}; a result outside the value bounds fails with code behavior-expression-type.
     */
    public String resolve(Map<String,Value> variables,BehaviorExpression.Environment environment){
        Output output=new Output(field);
        for(Part part:parts){
            switch(part){
                case Literal literal->output.known(literal.json(),literal.bytes());
                case Bound slot->{
                    Value value=variables.get(slot.variable());
                    if(value==null||slot.type()!=Type.ANY&&value.type()!=slot.type())throw new Invalid(slot.field(),"missing or mismatched runtime variable type");
                    write(output,value);
                }
                case Computed slot->{
                    if(environment==null)throw new Invalid(slot.field(),"expressions need an execution");
                    Object result=slot.expression().evaluate(environment);Value value;
                    try{value=BehaviorValues.natural(result);}catch(IllegalArgumentException invalid){throw new BehaviorExpression.Failed("behavior-expression-type",slot.field(),"the result exceeds the value bounds");}
                    write(output,value);
                }
                case Text slot->{
                    if(environment==null)throw new Invalid(slot.field(),"templates need an execution");
                    String text=slot.template().evaluate(environment);
                    try{new Value(Type.STRING,text);}catch(IllegalArgumentException invalid){throw new BehaviorExpression.Failed("behavior-expression-type",slot.field(),"the text exceeds "+BehaviorValues.MAX_STRING_BYTES+" UTF-8 bytes");}
                    output.string(text);
                }
            }
        }
        return output.text.toString();
    }
    private static void write(Output output,Value value){
        switch(value.type()){
            case BOOLEAN,INTEGER,DECIMAL->output.raw(value.value().toString());
            case STRING->output.string((String)value.value());
            case LIST,MAP->output.raw(BehaviorValues.canonical(value));
            case ANY->throw new IllegalStateException("Runtime values have concrete types");
        }
    }
    private static final class Builder {
        final Map<List<String>,Slot> targets;final List<Part> parts=new ArrayList<>();final Output output;int nodes;
        Builder(Map<List<String>,Slot> targets,String field){this.targets=targets;output=new Output(field);}
        void flush(){if(!output.text.isEmpty()){String text=output.text.toString();parts.add(new Literal(text,text.getBytes(StandardCharsets.UTF_8).length));output.text.setLength(0);}}
        void visit(JsonElement value,List<String> path,int depth){
            if(++nodes>MAX_LITERAL_NODES||depth>64)throw new Invalid(output.field,"argument literal tree exceeds bounded node/depth budget");
            Slot slot=targets.get(path);
            if(slot!=null){flush();parts.add(slot);output.reserve(4);return;}
            if(value.isJsonNull()){output.raw("null");return;}
            if(value.isJsonPrimitive()){
                JsonPrimitive primitive=value.getAsJsonPrimitive();
                if(primitive.isString())output.string(primitive.getAsString());else output.raw(primitive.getAsString());return;
            }
            if(value.isJsonObject()){
                output.raw("{");boolean first=true;
                for(var entry:value.getAsJsonObject().entrySet()){
                    if(!first)output.raw(",");first=false;output.string(entry.getKey());output.raw(":");visit(entry.getValue(),child(path,entry.getKey()),depth+1);
                }
                output.raw("}");
            }else{
                output.raw("[");JsonArray array=value.getAsJsonArray();
                for(int i=0;i<array.size();i++){if(i>0)output.raw(",");visit(array.get(i),child(path,Integer.toString(i)),depth+1);}output.raw("]");
            }
        }
        private static List<String> child(List<String> path,String key){List<String> next=new ArrayList<>(path);next.add(key);return next;}
    }
    /** Counts encoded output before appending; an expansion never constructs an oversized result. */
    private static final class Output {
        private static final char[] HEX="0123456789abcdef".toCharArray();
        final String field;final StringBuilder text=new StringBuilder();int bytes;
        Output(String field){this.field=field;}
        void reserve(int count){if(count>MAX_BYTES-bytes)throw new Invalid(field,"resolved arguments exceed 65536 UTF-8 bytes");bytes+=count;}
        void known(String value,int count){reserve(count);text.append(value);}
        void raw(String value){
            for(int i=0;i<value.length();i++){
                char c=value.charAt(i);
                if(Character.isHighSurrogate(c)&&i+1<value.length()&&Character.isLowSurrogate(value.charAt(i+1))){reserve(4);text.append(c).append(value.charAt(++i));}
                else if(Character.isSurrogate(c))throw new Invalid(field,"arguments must contain valid UTF-8 strings");
                else{reserve(c<128?1:c<2048?2:3);text.append(c);}
            }
        }
        void string(String value){
            raw("\"");
            for(int i=0;i<value.length();i++){
                char c=value.charAt(i);
                switch(c){
                    case '"'->raw("\\\"");case '\\'->raw("\\\\");case '\b'->raw("\\b");case '\f'->raw("\\f");case '\n'->raw("\\n");case '\r'->raw("\\r");case '\t'->raw("\\t");
                    default->{
                        if(c<32){reserve(6);text.append("\\u00").append(HEX[c>>>4]).append(HEX[c&15]);}
                        else if(Character.isHighSurrogate(c)&&i+1<value.length()&&Character.isLowSurrogate(value.charAt(i+1))){reserve(4);text.append(c).append(value.charAt(++i));}
                        else if(Character.isSurrogate(c))throw new Invalid(field,"arguments must contain valid UTF-8 strings");
                        else{reserve(c<128?1:c<2048?2:3);text.append(c);}
                    }
                }
            }
            raw("\"");
        }
    }
}
