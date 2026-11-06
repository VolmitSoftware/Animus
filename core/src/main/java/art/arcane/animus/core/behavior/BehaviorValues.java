package art.arcane.animus.core.behavior;

import art.arcane.animus.core.behavior.BehaviorProgram.Type;
import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import com.google.gson.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Bounds, canonical JSON and expression conversions of typed behavior values. Canonical JSON: booleans, integers and
 * strings as themselves, decimals always with a point or exponent, lists as arrays and maps as objects in insertion order.
 * Plain JSON reads integral tokens as integers and every other number as a decimal; null is never a value.
 */
public final class BehaviorValues {
    public static final int MAX_ITEMS=256,MAX_NESTING=8,MAX_KEY_LENGTH=256,MAX_STRING_BYTES=4096;
    /** The largest whole number an expression double carries exactly. */
    public static final double SAFE_WHOLE=9_007_199_254_740_992.0;
    private static final char[] HEX="0123456789abcdef".toCharArray();
    private BehaviorValues(){}

    /** The checked, immutable Java form of a value of a concrete type; throws IllegalArgumentException outside the bounds. */
    static Object checked(Type type,Object value){
        Object result=switch(type){
            case BOOLEAN->value instanceof Boolean?value:null;
            case INTEGER->value instanceof Long?value:null;
            case DECIMAL->value instanceof Double n&&Double.isFinite(n)?value:null;
            case STRING->value instanceof String s&&s.length()<=MAX_STRING_BYTES&&StandardCharsets.UTF_8.newEncoder().canEncode(s)&&s.getBytes(StandardCharsets.UTF_8).length<=MAX_STRING_BYTES?value:null;
            case LIST->{
                if(!(value instanceof List<?> items)||items.size()>MAX_ITEMS)yield null;
                List<Value> copy=new ArrayList<>(items.size());
                for(Object item:items){if(!(item instanceof Value element))yield null;copy.add(element);}
                yield List.copyOf(copy);
            }
            case MAP->{
                if(!(value instanceof Map<?,?> entries)||entries.size()>MAX_ITEMS)yield null;
                LinkedHashMap<String,Value> copy=new LinkedHashMap<>();
                for(Map.Entry<?,?> entry:entries.entrySet()){
                    if(!(entry.getKey() instanceof String key)||key.isEmpty()||key.length()>MAX_KEY_LENGTH||!StandardCharsets.UTF_8.newEncoder().canEncode(key)||!(entry.getValue() instanceof Value element))yield null;
                    copy.put(key,element);
                }
                yield Collections.unmodifiableMap(copy);
            }
            case ANY->null;
        };
        if(result==null)throw new IllegalArgumentException("Invalid typed value");
        if((type==Type.LIST||type==Type.MAP)&&(depth(result)>MAX_NESTING||canonicalBytes(type,result)>MAX_STRING_BYTES))throw new IllegalArgumentException("Typed value exceeds its bounds");
        return result;
    }
    private static int depth(Object value){
        int deepest=0;
        Collection<?> children=value instanceof List<?> items?items:value instanceof Map<?,?> entries?entries.values():List.of();
        for(Object child:children){Object inner=((Value)child).value();if(inner instanceof List<?>||inner instanceof Map<?,?>)deepest=Math.max(deepest,depth(inner));}
        return deepest+1;
    }
    private static int canonicalBytes(Type type,Object value){StringBuilder out=new StringBuilder();write(out,type,value);return out.toString().getBytes(StandardCharsets.UTF_8).length;}

    public static String canonical(Value value){StringBuilder out=new StringBuilder();write(out,value.type(),value.value());return out.toString();}
    private static void write(StringBuilder out,Type type,Object value){
        switch(type){
            case BOOLEAN,INTEGER,DECIMAL->out.append(value);
            case STRING->string(out,(String)value);
            case LIST->{out.append('[');boolean first=true;for(Object item:(List<?>)value){if(!first)out.append(',');first=false;Value element=(Value)item;write(out,element.type(),element.value());}out.append(']');}
            case MAP->{out.append('{');boolean first=true;for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()){if(!first)out.append(',');first=false;string(out,(String)entry.getKey());out.append(':');Value element=(Value)entry.getValue();write(out,element.type(),element.value());}out.append('}');}
            case ANY->throw new IllegalArgumentException("Invalid typed value");
        }
    }
    static void string(StringBuilder out,String value){
        out.append('"');
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            switch(c){
                case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\b'->out.append("\\b");case '\f'->out.append("\\f");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");
                default->{if(c<32)out.append("\\u00").append(HEX[c>>>4]).append(HEX[c&15]);else out.append(c);}
            }
        }
        out.append('"');
    }
    /** The canonical encoding as a Gson tree. */
    public static JsonElement json(Value value){
        return switch(value.type()){
            case BOOLEAN->new JsonPrimitive((Boolean)value.value());
            case INTEGER,DECIMAL->new JsonPrimitive((Number)value.value());
            case STRING->new JsonPrimitive((String)value.value());
            case LIST->{JsonArray array=new JsonArray();for(Object item:(List<?>)value.value())array.add(json((Value)item));yield array;}
            case MAP->{JsonObject object=new JsonObject();for(Map.Entry<?,?> entry:((Map<?,?>)value.value()).entrySet())object.add((String)entry.getKey(),json((Value)entry.getValue()));yield object;}
            case ANY->throw new IllegalArgumentException("Invalid typed value");
        };
    }
    /** Plain JSON to a value; throws IllegalArgumentException for null or values outside the bounds. */
    public static Value read(JsonElement element){
        if(element==null||element.isJsonNull())throw new IllegalArgumentException("null is not a value");
        if(element.isJsonArray()){List<Value> items=new ArrayList<>();for(JsonElement item:element.getAsJsonArray()){if(items.size()>=MAX_ITEMS)throw new IllegalArgumentException("list exceeds "+MAX_ITEMS+" items");items.add(read(item));}return new Value(Type.LIST,items);}
        if(element.isJsonObject()){Map<String,Value> entries=new LinkedHashMap<>();for(Map.Entry<String,JsonElement> entry:element.getAsJsonObject().entrySet()){if(entries.size()>=MAX_ITEMS)throw new IllegalArgumentException("map exceeds "+MAX_ITEMS+" entries");entries.put(entry.getKey(),read(entry.getValue()));}return new Value(Type.MAP,entries);}
        JsonPrimitive primitive=element.getAsJsonPrimitive();
        if(primitive.isBoolean())return new Value(Type.BOOLEAN,primitive.getAsBoolean());
        if(primitive.isString())return new Value(Type.STRING,primitive.getAsString());
        BigDecimal number=primitive.getAsBigDecimal();
        if(number.toString().matches("-?[0-9]+"))return new Value(Type.INTEGER,number.longValueExact());
        return new Value(Type.DECIMAL,number.doubleValue());
    }
    /** A declared initial or literal: scalars keep the compiler's first rules, list and map need their JSON shape, any takes every value. */
    public static Value read(JsonElement element,Type type){
        if(element==null||element.isJsonNull())throw new IllegalArgumentException("null is not a value");
        return switch(type){
            case ANY->read(element);
            case LIST->{if(!element.isJsonArray())throw new IllegalArgumentException("expected array");yield read(element);}
            case MAP->{if(!element.isJsonObject())throw new IllegalArgumentException("expected object");yield read(element);}
            default->{
                if(!element.isJsonPrimitive())throw new IllegalArgumentException("expected scalar");JsonPrimitive primitive=element.getAsJsonPrimitive();
                Object value=switch(type){
                    case BOOLEAN->{if(!primitive.isBoolean())throw new IllegalArgumentException();yield primitive.getAsBoolean();}
                    case INTEGER->{if(!primitive.isNumber())throw new IllegalArgumentException();yield primitive.getAsBigDecimal().longValueExact();}
                    case DECIMAL->{if(!primitive.isNumber())throw new IllegalArgumentException();yield primitive.getAsDouble();}
                    default->{if(!primitive.isString())throw new IllegalArgumentException();yield primitive.getAsString();}
                };
                yield new Value(type,value);
            }
        };
    }

    /** The expression form: numbers become doubles, lists and maps become immutable Java collections. */
    public static Object expression(Value value){
        return switch(value.type()){
            case INTEGER->((Long)value.value()).doubleValue();
            case LIST->{List<Object> items=new ArrayList<>();for(Object item:(List<?>)value.value())items.add(expression((Value)item));yield Collections.unmodifiableList(items);}
            case MAP->{LinkedHashMap<String,Object> entries=new LinkedHashMap<>();for(Map.Entry<?,?> entry:((Map<?,?>)value.value()).entrySet())entries.put((String)entry.getKey(),expression((Value)entry.getValue()));yield Collections.unmodifiableMap(entries);}
            default->value.value();
        };
    }
    /** An expression result in its natural type: a whole number within 2^53 is an integer, other numbers decimals. */
    public static Value natural(Object value){
        return switch(value){
            case Boolean flag->new Value(Type.BOOLEAN,flag);
            case String text->new Value(Type.STRING,text);
            case Double number->whole(number)?new Value(Type.INTEGER,(long)(double)number):new Value(Type.DECIMAL,number);
            case Number number->natural(number.doubleValue());
            case List<?> items->{if(items.size()>MAX_ITEMS)throw new IllegalArgumentException("list exceeds "+MAX_ITEMS+" items");List<Value> values=new ArrayList<>();for(Object item:items)values.add(natural(item));yield new Value(Type.LIST,values);}
            case Map<?,?> entries->{if(entries.size()>MAX_ITEMS)throw new IllegalArgumentException("map exceeds "+MAX_ITEMS+" entries");Map<String,Value> values=new LinkedHashMap<>();for(Map.Entry<?,?> entry:entries.entrySet())values.put(String.valueOf(entry.getKey()),natural(entry.getValue()));yield new Value(Type.MAP,values);}
            case null->throw new IllegalArgumentException("nothing is not a value");
            default->new Value(Type.STRING,value.toString());
        };
    }
    private static boolean whole(double number){return Double.isFinite(number)&&number==Math.rint(number)&&Math.abs(number)<=SAFE_WHOLE;}
    /** Converts an expression result to a declared type; throws IllegalArgumentException with the reason when it cannot. */
    public static Value coerce(Object value,Type type){
        try{
            return switch(type){
                case ANY->natural(value);
                case STRING->new Value(Type.STRING,art.arcane.animus.core.gui.GuiExpression.text(value));
                case INTEGER->{if(value instanceof Double number&&whole(number))yield new Value(Type.INTEGER,(long)(double)number);throw new IllegalArgumentException("expected a whole number within 2^53, got "+describe(value));}
                case DECIMAL->{if(value instanceof Double number&&Double.isFinite(number))yield new Value(Type.DECIMAL,number);throw new IllegalArgumentException("expected a number, got "+describe(value));}
                case BOOLEAN->{if(value instanceof Boolean flag)yield new Value(Type.BOOLEAN,flag);throw new IllegalArgumentException("expected true or false, got "+describe(value));}
                case LIST->{if(value instanceof List<?>)yield natural(value);throw new IllegalArgumentException("expected a list, got "+describe(value));}
                case MAP->{if(value instanceof Map<?,?>)yield natural(value);throw new IllegalArgumentException("expected a map, got "+describe(value));}
            };
        }catch(IllegalArgumentException invalid){
            if(invalid.getMessage()!=null&&!invalid.getMessage().startsWith("Invalid typed value")&&!invalid.getMessage().startsWith("Typed value"))throw invalid;
            throw new IllegalArgumentException("the "+name(type)+" value exceeds the value bounds");
        }
    }
    /** Converts a stored value to another declared type, as an assignment of that value would. */
    public static Value coerce(Value value,Type type){return type==Type.ANY||value.type()==type?value:coerce(expression(value),type);}
    static String describe(Object value){
        return switch(value){
            case Double ignored->"number";case String ignored->"string";case Boolean ignored->"boolean";case List<?> ignored->"list";case Map<?,?> ignored->"map";case null->"nothing";default->value.getClass().getSimpleName();
        };
    }
    public static String name(Type type){return type.name().toLowerCase(Locale.ROOT);}
}
