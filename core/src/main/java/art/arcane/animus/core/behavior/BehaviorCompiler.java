package art.arcane.animus.core.behavior;

import art.arcane.animus.api.*;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.behavior.BehaviorProgram.*;
import art.arcane.animus.core.behavior.trigger.TriggerBinding;
import art.arcane.animus.core.behavior.trigger.TriggerCompiler;
import art.arcane.animus.core.behavior.trigger.TriggerKind;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict first-gate compiler. Invalid/unsupported source is diagnosed; this compiler never writes source files. */
public final class BehaviorCompiler {
    public static final int MAX_BYTES=256*1024,MAX_NODES=2048,MAX_DEPTH=32,MAX_ROUTINES=32,MAX_VARIABLES=32;
    public record Result(Optional<BehaviorProgram> program,List<Diagnostic> diagnostics){public Result{diagnostics=List.copyOf(diagnostics);}}
    private static final Set<TriggerKind> PLAYERLESS=EnumSet.of(TriggerKind.SPAWN,TriggerKind.DESPAWN,TriggerKind.DAMAGE,TriggerKind.DEATH,TriggerKind.WAYPOINT,TriggerKind.TIMER);
    private final Map<String,Value> variables=new LinkedHashMap<>();
    private final Map<String,ScopedVariable> scoped=new LinkedHashMap<>();
    private final Map<String,Node> routines=new LinkedHashMap<>();
    private final List<Call> references=new ArrayList<>();
    private int nodes;
    private static final class Invalid extends RuntimeException {final String path;Invalid(String path,String message){super(message);this.path=path;}}
    public static Result compile(String source){
        try{
            if(source==null || source.length()>MAX_BYTES || !StandardCharsets.UTF_8.newEncoder().canEncode(source) || source.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
                throw new Invalid("$","expected valid UTF-8 JSON within 262144 bytes");
            BehaviorProgram program=new BehaviorCompiler().program(JsonDocuments.object(source));
            return new Result(Optional.of(program),warnings(program));
        }catch(Invalid invalid){return rejected(invalid.path,invalid.getMessage());}
        catch(java.io.IOException invalid){return rejected("$","invalid strict JSON object");}
    }
    /** A player-less trigger cannot start a behavior declaring player variables: the runtime refuses every such firing. Exact and repeatable for retained programs. */
    public static List<Diagnostic> warnings(BehaviorProgram program){
        String variable=null;
        for(Map.Entry<String,ScopedVariable> declared:new TreeMap<>(program.scoped()).entrySet())if(declared.getValue().scope().requiresPlayer()){variable=declared.getKey();break;}
        if(variable==null)return List.of();
        List<Diagnostic> warnings=new ArrayList<>();
        for(int index=0;index<program.triggers().size();index++){
            TriggerKind kind=program.triggers().get(index).spec().kind();
            if(!PLAYERLESS.contains(kind))continue;
            warnings.add(new Diagnostic(Diagnostic.Severity.WARNING,"behavior-trigger-needs-player","$.triggers["+index+"]",
                    "A "+kind.name().toLowerCase(Locale.ROOT)+" trigger has no player, and this behavior declares the player variable "+variable+", so every firing will be refused"));
        }
        return List.copyOf(warnings);
    }
    private static Result rejected(String path,String message){return new Result(Optional.empty(),List.of(new Diagnostic(Diagnostic.Severity.ERROR,"behavior-invalid",diagnosticPath(path),message)));}
    private static String diagnosticPath(String path){
        StringBuilder safe=new StringBuilder();
        for(int i=0;i<path.length()&&safe.length()<220;i++){
            char value=path.charAt(i);
            if(Character.isHighSurrogate(value)&&i+1<path.length()&&Character.isLowSurrogate(path.charAt(i+1)))safe.append(value).append(path.charAt(++i));
            else if(Character.isSurrogate(value))safe.append(String.format("\\u%04x",(int)value));else safe.append(value);
        }
        return safe.toString();
    }
    private BehaviorProgram program(JsonObject object){
        keys(object,"$","schemaVersion","id","entry","variables","routines","triggers");integer(object,"schemaVersion","$",1,1);
        String id=text(object,"id","$");
        if(id.length()>256||!id.matches("[a-z0-9_-]+:[a-z0-9_/-]+"))throw new Invalid("$.id","expected a lowercase namespace:path behavior reference within 256 characters");
        if(id.matches("animus:(flags|quests)(/.*)?"))throw new Invalid("$.id","reserved for built-in flag and quest state");
        String entry=identifier(text(object,"entry","$"),"$.entry");
        if(object.has("variables")){
            JsonObject vars=object(object.get("variables"),"$.variables");if(vars.size()>MAX_VARIABLES)throw new Invalid("$.variables","at most 32 variables");
            for(var item:vars.entrySet()){
                String path="$.variables."+item.getKey();identifier(item.getKey(),path);JsonObject var=object(item.getValue(),path);keys(var,path,"type","initial","scope","lifetime");
                ScopedVariable declaration=scope(var,path);if(declaration!=null)scoped.put(item.getKey(),declaration);
                Type type;try{type=Type.valueOf(text(var,"type",path).toUpperCase(Locale.ROOT));}catch(IllegalArgumentException unsupported){throw new Invalid(path+".type","expected boolean, integer, decimal or string");}
                variables.put(item.getKey(),value(var.get("initial"),type,path+".initial"));
            }
        }
        JsonObject definitions=object(object.get("routines"),"$.routines");if(definitions.size()<1||definitions.size()>MAX_ROUTINES)throw new Invalid("$.routines","expected 1..32 routines");
        for(var item:definitions.entrySet()){
            identifier(item.getKey(),"$.routines."+item.getKey());routines.put(item.getKey(),node(item.getValue(),"$.routines."+item.getKey(),0));
        }
        if(!routines.containsKey(entry))throw new Invalid("$.entry","unknown routine");
        for(Call call:references)if(!routines.containsKey(call.routine()))throw new Invalid(call.path()+".routine","unknown routine");
        List<TriggerBinding> triggers;
        try{triggers=object.has("triggers")?TriggerCompiler.compile(object.get("triggers"),routines.keySet()):List.of();}
        catch(TriggerCompiler.Invalid invalid){throw new Invalid(invalid.field(),invalid.getMessage());}
        return new BehaviorProgram(id,entry,variables,scoped,routines,nodes,triggers);
    }
    /** Null means the default execution scope. Scope and lifetime words are exact: no case folding or coercion. */
    private static ScopedVariable scope(JsonObject variable,String path){
        String name=variable.has("scope")?text(variable,"scope",path):"execution";
        Scope scope=switch(name){
            case "execution"->null;
            case "npc"->Scope.NPC;
            case "player"->Scope.PLAYER;
            case "relationship"->Scope.RELATIONSHIP;
            case "global"->Scope.GLOBAL;
            default->throw new Invalid(path+".scope","expected execution, npc, player, relationship or global");
        };
        if(scope==null){
            if(variable.has("lifetime"))throw new Invalid(path+".lifetime","execution variables end with their execution and declare no lifetime");
            return null;
        }
        if(!variable.has("lifetime"))throw new Invalid(path+".lifetime","required for "+name+" scope: expected transient or persistent");
        Lifetime lifetime=switch(text(variable,"lifetime",path)){
            case "transient"->Lifetime.TRANSIENT;
            case "persistent"->Lifetime.PERSISTENT;
            default->throw new Invalid(path+".lifetime","expected transient or persistent");
        };
        return new ScopedVariable(scope,lifetime);
    }
    private Node node(JsonElement element,String path,int depth){
        if(depth>MAX_DEPTH)throw new Invalid(path,"node nesting exceeds 32");if(++nodes>MAX_NODES)throw new Invalid(path,"node budget exceeds 2048");
        JsonObject obj=object(element,path);String type=text(obj,"type",path);
        return switch(type){
            case "sequence"->{keys(obj,path,"type","steps");yield new Sequence(path,children(obj.get("steps"),path+".steps",depth,0,128));}
            case "action"->{keys(obj,path,"type","capability","arguments","bindings");yield new Action(path,capability(text(obj,"capability",path),path+".capability"),arguments(obj,path));}
            case "condition"->{keys(obj,path,"type","capability","arguments","bindings","then","else");yield new Condition(path,capability(text(obj,"capability",path),path+".capability"),arguments(obj,path),node(obj.get("then"),path+".then",depth+1),obj.has("else")?node(obj.get("else"),path+".else",depth+1):empty(path+".else"));}
            case "wait"->{keys(obj,path,"type","ticks");yield new Wait(path,integer(obj,"ticks",path,1,72000));}
            case "repeat"->{keys(obj,path,"type","count","body");yield new Repeat(path,integer(obj,"count",path,0,128),node(obj.get("body"),path+".body",depth+1));}
            case "parallel"->{keys(obj,path,"type","branches");yield new Parallel(path,children(obj.get("branches"),path+".branches",depth,1,8));}
            case "call"->{keys(obj,path,"type","routine");Call call=new Call(path,identifier(text(obj,"routine",path),path+".routine"));references.add(call);yield call;}
            case "set"->{keys(obj,path,"type","variable","value");String name=text(obj,"variable",path);Value declared=variables.get(name);if(declared==null)throw new Invalid(path+".variable","unknown declared variable");JsonElement input=obj.get("value");Operand operand;
                if(input!=null&&input.isJsonObject()){JsonObject ref=input.getAsJsonObject();keys(ref,path+".value","variable");String source=text(ref,"variable",path+".value");Value other=variables.get(source);if(other==null||other.type()!=declared.type())throw new Invalid(path+".value.variable","unknown or mismatched variable type");operand=new Variable(source);}
                else operand=new Literal(value(input,declared.type(),path+".value"));yield new SetValue(path,name,operand);}
            default->throw new Invalid(path+".type","unsupported node type");
        };
    }
    private Node empty(String path){if(++nodes>MAX_NODES)throw new Invalid(path,"node budget exceeds 2048");return new Sequence(path,List.of());}
    private List<Node> children(JsonElement raw,String path,int depth,int minimum,int maximum){
        if(raw==null||!raw.isJsonArray())throw new Invalid(path,"expected array");JsonArray array=raw.getAsJsonArray();if(array.size()<minimum||array.size()>maximum)throw new Invalid(path,"array size outside "+minimum+".."+maximum);
        List<Node> result=new ArrayList<>();for(int i=0;i<array.size();i++)result.add(node(array.get(i),path+"["+i+"]",depth+1));return result;
    }
    private ArgumentTemplate arguments(JsonObject object,String path){
        JsonObject args=object.has("arguments")?object(object.get("arguments"),path+".arguments"):new JsonObject();
        JsonObject bindings=object.has("bindings")?object(object.get("bindings"),path+".bindings"):new JsonObject();
        try{return ArgumentTemplate.compile(args,bindings,variables,path);}
        catch(ArgumentTemplate.Invalid invalid){throw new Invalid(invalid.field(),invalid.getMessage());}
    }
    private static Value value(JsonElement element,Type type,String path){
        try{
            if(element==null||!element.isJsonPrimitive())throw new IllegalArgumentException();JsonPrimitive primitive=element.getAsJsonPrimitive();
            Object value=switch(type){
                case BOOLEAN->{if(!primitive.isBoolean())throw new IllegalArgumentException();yield primitive.getAsBoolean();}
                case INTEGER->{if(!primitive.isNumber())throw new IllegalArgumentException();yield primitive.getAsBigDecimal().longValueExact();}
                case DECIMAL->{if(!primitive.isNumber())throw new IllegalArgumentException();yield primitive.getAsDouble();}
                case STRING->{if(!primitive.isString())throw new IllegalArgumentException();yield primitive.getAsString();}
            };return new Value(type,value);
        }catch(RuntimeException invalid){throw new Invalid(path,"expected bounded "+type.name().toLowerCase(Locale.ROOT)+" value");}
    }
    private static JsonObject object(JsonElement element,String path){if(element==null||!element.isJsonObject())throw new Invalid(path,"expected object");return element.getAsJsonObject();}
    private static void keys(JsonObject object,String path,String...allowed){Set<String> supported=Set.of(allowed);for(String key:object.keySet())if(!supported.contains(key))throw new Invalid(path+"."+key,"unsupported field");}
    private static String text(JsonObject object,String key,String path){JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().isBlank())throw new Invalid(path+"."+key,"expected nonblank string");return value.getAsString();}
    private static int integer(JsonObject object,String key,String path,int minimum,int maximum){try{JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new ArithmeticException();int number=value.getAsBigDecimal().intValueExact();if(number<minimum||number>maximum)throw new ArithmeticException();return number;}catch(RuntimeException invalid){throw new Invalid(path+"."+key,"expected integer "+minimum+".."+maximum);}}
    private static String identifier(String value,String path){if(!value.matches("[a-z0-9][a-z0-9_-]{0,63}"))throw new Invalid(path,"expected a lowercase identifier within 64 characters");return value;}
    private static Capabilities.Key capability(String value,String path){String[] parts=value.split(":",-1);if(parts.length!=2)throw new Invalid(path,"expected namespace:name");try{return new Capabilities.Key(parts[0],parts[1]);}catch(IllegalArgumentException invalid){throw new Invalid(path,"expected namespace:name identifiers");}}
}
