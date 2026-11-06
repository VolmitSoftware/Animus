package art.arcane.animus.core.behavior;

import art.arcane.animus.api.*;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.behavior.BehaviorProgram.*;
import art.arcane.animus.core.behavior.trigger.TriggerBinding;
import art.arcane.animus.core.behavior.trigger.TriggerCompiler;
import art.arcane.animus.core.behavior.trigger.TriggerKind;
import art.arcane.animus.core.behavior.schema.Suggestions;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict first-gate compiler. Invalid/unsupported source is diagnosed; this compiler never writes source files. */
public final class BehaviorCompiler {
    public static final int MAX_BYTES=256*1024,MAX_NODES=2048,MAX_DEPTH=32,MAX_ROUTINES=32,MAX_VARIABLES=128,MAX_WAIT_TICKS=72000,MAX_REPEAT=128,MAX_STEPS=128,MAX_BRANCHES=8,MAX_STRING_BYTES=4096,MAX_LOOP=1024;
    /** Top-level fields the compiler reads. Catalog sources also carry {@code revision}, which the catalog removes before compiling. */
    public static final List<String> DOCUMENT_FIELDS=List.of("schemaVersion","id","server","entry","variables","routines","triggers");
    /** The JSON words of {@link Type}, in declaration order. */
    public static final List<String> TYPE_NAMES=typeNames();
    /** Keys that make a JSON object an operand rather than a map literal. */
    public static final List<String> OPERAND_KEYS=List.of("variable","expression","template");
    public record Result(Optional<BehaviorProgram> program,List<Diagnostic> diagnostics){public Result{diagnostics=List.copyOf(diagnostics);}}
    private final Map<String,Value> variables=new LinkedHashMap<>();
    private final Map<String,Type> types=new LinkedHashMap<>();
    private final Map<String,ScopedVariable> scoped=new LinkedHashMap<>();
    private final Map<String,Node> routines=new LinkedHashMap<>();
    private final List<Call> references=new ArrayList<>();
    private int nodes;
    /** Lexical placement: break and continue need an enclosing loop of this routine; return may not sit in a parallel branch. */
    private boolean inLoop,inParallel;
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
            if(kind.carriesPlayer())continue;
            warnings.add(new Diagnostic(Diagnostic.Severity.WARNING,"behavior-trigger-needs-player","$.triggers["+index+"]",
                    "A "+kind.name().toLowerCase(Locale.ROOT)+" trigger has no player, and this behavior declares the player variable "+variable+", so every firing will be refused"));
        }
        return List.copyOf(warnings);
    }
    private static List<String> typeNames(){List<String> names=new ArrayList<>();for(Type type:Type.values())names.add(BehaviorValues.name(type));return List.copyOf(names);}
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
        keys(object,"$","document",DOCUMENT_FIELDS);integer(object,"schemaVersion","$",1,1);
        String id=text(object,"id","$");
        if(id.length()>256||!id.matches("[a-z0-9_-]+:[a-z0-9_/-]+"))throw new Invalid("$.id","expected a lowercase namespace:path behavior reference within 256 characters");
        if(id.matches("animus:(flags|quests)(/.*)?"))throw new Invalid("$.id","reserved for built-in flag and quest state");
        String entry=identifier(text(object,"entry","$"),"$.entry");
        boolean server=server(object);
        if(object.has("variables")){
            JsonObject vars=object(object.get("variables"),"$.variables");if(vars.size()>MAX_VARIABLES)throw new Invalid("$.variables","at most "+MAX_VARIABLES+" variables");
            for(var item:vars.entrySet()){
                String path="$.variables."+item.getKey();identifier(item.getKey(),path);
                if(BehaviorExpression.RESERVED.contains(item.getKey()))throw new Invalid(path,"reserved for expressions; choose another variable name");
                JsonObject var=object(item.getValue(),path);keys(var,path,"variable",List.of("type","initial","scope","lifetime"));
                ScopedVariable declaration=scope(var,path);if(declaration!=null)scoped.put(item.getKey(),declaration);
                if(server&&declaration!=null&&(declaration.scope()==Scope.NPC||declaration.scope()==Scope.RELATIONSHIP))throw new Invalid(path+".scope","a server behavior has no NPC: expected execution, player or global");
                String word=text(var,"type",path);
                if(!TYPE_NAMES.contains(word))throw new Invalid(path+".type","expected boolean, integer, decimal, string, list, map or any"+schemaHint(word,TYPE_NAMES));
                Type type=Type.valueOf(word.toUpperCase(Locale.ROOT));
                types.put(item.getKey(),type);variables.put(item.getKey(),value(var.get("initial"),type,path+".initial"));
            }
        }
        JsonObject definitions=object(object.get("routines"),"$.routines");if(definitions.size()<1||definitions.size()>MAX_ROUTINES)throw new Invalid("$.routines","expected 1..32 routines");
        for(var item:definitions.entrySet()){
            identifier(item.getKey(),"$.routines."+item.getKey());inLoop=false;inParallel=false;routines.put(item.getKey(),node(item.getValue(),"$.routines."+item.getKey(),0));
        }
        if(!routines.containsKey(entry))throw new Invalid("$.entry","unknown routine; declared: "+Suggestions.list(new ArrayList<>(routines.keySet()))+schemaHint(entry,new ArrayList<>(routines.keySet())));
        for(Call call:references)if(!routines.containsKey(call.routine()))throw new Invalid(call.path()+".routine","unknown routine; declared: "+Suggestions.list(new ArrayList<>(routines.keySet()))+schemaHint(call.routine(),new ArrayList<>(routines.keySet())));
        List<TriggerBinding> triggers;
        try{triggers=object.has("triggers")?TriggerCompiler.compile(object.get("triggers"),routines.keySet(),server):List.of();}
        catch(TriggerCompiler.Invalid invalid){throw new Invalid(invalid.field(),invalid.getMessage());}
        return new BehaviorProgram(id,entry,variables,types,scoped,routines,nodes,triggers,server);
    }
    /** {@code "server": true} makes a server-wide behavior: no NPC, server events only, execution, player and global variables only. */
    private static boolean server(JsonObject object){
        JsonElement value=object.get("server");if(value==null)return false;
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new Invalid("$.server","expected true or false");
        return value.getAsBoolean();
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
        BehaviorNodeType kind=BehaviorNodeType.find(type).orElseThrow(()->new Invalid(path+".type","unsupported node type; expected one of: "+Suggestions.list(BehaviorNodeType.names())+Suggestions.didYouMean(type,BehaviorNodeType.names())));
        keys(obj,path,kind.jsonName(),kind.keys());
        return switch(kind){
            case SEQUENCE->new Sequence(path,children(obj.get("steps"),path+".steps",depth,0,MAX_STEPS));
            case ACTION->new Action(path,capability(text(obj,"capability",path),path+".capability"),arguments(obj,path));
            case CONDITION->{
                if(obj.has("expression")){
                    for(String field:List.of("capability","arguments","bindings","expressions","templates"))if(obj.has(field))throw new Invalid(path+"."+field,"an expression condition takes no "+field);
                    BehaviorExpression test=expression(obj,"expression",path);
                    yield new ExpressionCondition(path,test,node(obj.get("then"),path+".then",depth+1),obj.has("else")?node(obj.get("else"),path+".else",depth+1):empty(path+".else"));
                }
                yield new Condition(path,capability(text(obj,"capability",path),path+".capability"),arguments(obj,path),node(obj.get("then"),path+".then",depth+1),obj.has("else")?node(obj.get("else"),path+".else",depth+1):empty(path+".else"));
            }
            case WAIT->new Wait(path,integer(obj,"ticks",path,1,MAX_WAIT_TICKS));
            case REPEAT->{
                int maximum=obj.has("maximum")?integer(obj,"maximum",path,1,MAX_LOOP):MAX_REPEAT;
                JsonElement count=obj.get("count");Operand computed=null;int literal=0;
                if(count!=null&&count.isJsonObject())computed=operand(count,Type.INTEGER,path+".count");else literal=integer(obj,"count",path,0,MAX_REPEAT);
                String index=obj.has("index")?execution(text(obj,"index",path),path+".index",Type.INTEGER,Type.DECIMAL,Type.ANY):null;
                yield new Repeat(path,literal,computed,maximum,index,loopBody(obj,path,depth));
            }
            case FOREACH->{
                JsonElement source=obj.get("list");if(source==null)throw new Invalid(path+".list","expected an operand naming a list or map");
                Operand list=operand(source,Type.ANY,path+".list");
                String as=execution(text(obj,"as",path),path+".as");
                String index=obj.has("index")?execution(text(obj,"index",path),path+".index",Type.INTEGER,Type.DECIMAL,Type.ANY):null;
                String key=obj.has("key")?execution(text(obj,"key",path),path+".key",Type.STRING,Type.ANY):null;
                int maximum=obj.has("maximum")?integer(obj,"maximum",path,1,MAX_LOOP):MAX_REPEAT;
                yield new Foreach(path,list,as,index,key,maximum,loopBody(obj,path,depth));
            }
            case WHILE->{
                BehaviorExpression test=expression(obj,"expression",path);
                int maximum=obj.has("maximum")?integer(obj,"maximum",path,1,MAX_LOOP):MAX_REPEAT;
                yield new While(path,test,maximum,loopBody(obj,path,depth));
            }
            case BREAK->{if(!inLoop)throw new Invalid(path,"break must be inside a repeat, foreach or while body of this routine, outside parallel branches");yield new Break(path);}
            case CONTINUE->{if(!inLoop)throw new Invalid(path,"continue must be inside a repeat, foreach or while body of this routine, outside parallel branches");yield new Continue(path);}
            case RETURN->{if(inParallel)throw new Invalid(path,"return cannot be inside a parallel branch");yield new Return(path,obj.has("value")?operand(obj.get("value"),null,path+".value"):null);}
            case PARALLEL->{
                boolean loop=inLoop,parallel=inParallel;inLoop=false;inParallel=true;
                try{yield new Parallel(path,children(obj.get("branches"),path+".branches",depth,1,MAX_BRANCHES));}finally{inLoop=loop;inParallel=parallel;}
            }
            case CALL->{
                String routine=identifier(text(obj,"routine",path),path+".routine");
                Map<String,Operand> arguments=new LinkedHashMap<>();
                if(obj.has("arguments")){
                    JsonObject given=object(obj.get("arguments"),path+".arguments");
                    for(var argument:given.entrySet()){String field=path+".arguments."+argument.getKey();declared(argument.getKey(),field);arguments.put(argument.getKey(),operand(argument.getValue(),types.get(argument.getKey()),field));}
                }
                String result=obj.has("result")?declared(text(obj,"result",path),path+".result"):null;
                Call call=new Call(path,routine,arguments,result);references.add(call);yield call;
            }
            case SET->{String name=text(obj,"variable",path);if(!variables.containsKey(name))throw new Invalid(path+".variable","unknown declared variable"+schemaHint(name,new ArrayList<>(variables.keySet())));
                yield new SetValue(path,name,operand(obj.get("value"),types.get(name),path+".value"));}
        };
    }
    private Node loopBody(JsonObject obj,String path,int depth){
        boolean loop=inLoop;inLoop=true;
        try{return node(obj.get("body"),path+".body",depth+1);}finally{inLoop=loop;}
    }
    /**
     * Literal JSON of the target type, or an object whose key is variable, expression or template. A map literal whose
     * keys include one of those words is read as an operand unless the target holds maps and the object has other keys.
     * A null target, used by return, reads literals with the plain JSON rules.
     */
    private Operand operand(JsonElement input,Type target,String field){
        if(input!=null&&input.isJsonObject()){
            JsonObject object=input.getAsJsonObject();
            boolean named=false;for(String key:OPERAND_KEYS)named|=object.has(key);
            boolean literalMap=(target==null||target==Type.MAP||target==Type.ANY)&&object.size()>1;
            if(named&&!literalMap){
                if(object.size()!=1)keys(object,field,"operand",List.of(object.has("variable")?"variable":object.has("expression")?"expression":"template"));
                if(object.has("variable")){
                    String source=text(object,"variable",field);Type other=types.get(source);
                    if(other==null||!(target==null||target==Type.ANY||other==Type.ANY||other==target))throw new Invalid(field+".variable","unknown or mismatched variable type");
                    return new Variable(source);
                }
                if(object.has("expression"))return new Expression(field+".expression",expression(object,"expression",field));
                if(target!=null&&target!=Type.STRING&&target!=Type.ANY)throw new Invalid(field+".template","a template makes text, and the target is "+BehaviorValues.name(target));
                String source=text(object,"template",field);
                try{return new Template(field+".template",BehaviorTemplate.compile(source,variables.keySet(),field+".template"));}
                catch(BehaviorExpression.Invalid invalid){throw new Invalid(invalid.field(),invalid.getMessage());}
            }
        }
        if(target==null){try{return new Literal(BehaviorValues.read(input));}catch(RuntimeException invalid){throw new Invalid(field,"expected a bounded JSON value");}}
        return new Literal(value(input,target,field));
    }
    private BehaviorExpression expression(JsonObject object,String key,String path){
        String source=text(object,key,path);
        try{return BehaviorExpression.compile(source,variables.keySet(),path+"."+key);}
        catch(BehaviorExpression.Invalid invalid){throw new Invalid(invalid.field(),invalid.getMessage());}
    }
    private String declared(String name,String field){if(!variables.containsKey(name))throw new Invalid(field,"unknown declared variable"+schemaHint(name,new ArrayList<>(variables.keySet())));return name;}
    /** A declared execution variable, optionally limited to some declared types. */
    private String execution(String name,String field,Type... allowed){
        declared(name,field);
        if(scoped.containsKey(name))throw new Invalid(field,"loop variables must be execution variables");
        if(allowed.length>0&&!List.of(allowed).contains(types.get(name))){List<String> words=new ArrayList<>();for(Type type:allowed)words.add(BehaviorValues.name(type));throw new Invalid(field,"expected a variable of type "+String.join(", ",words));}
        return name;
    }
    private Node empty(String path){if(++nodes>MAX_NODES)throw new Invalid(path,"node budget exceeds 2048");return new Sequence(path,List.of());}
    private List<Node> children(JsonElement raw,String path,int depth,int minimum,int maximum){
        if(raw==null||!raw.isJsonArray())throw new Invalid(path,"expected array");JsonArray array=raw.getAsJsonArray();if(array.size()<minimum||array.size()>maximum)throw new Invalid(path,"array size outside "+minimum+".."+maximum);
        List<Node> result=new ArrayList<>();for(int i=0;i<array.size();i++)result.add(node(array.get(i),path+"["+i+"]",depth+1));return result;
    }
    private ArgumentTemplate arguments(JsonObject object,String path){
        JsonObject args=object.has("arguments")?object(object.get("arguments"),path+".arguments"):new JsonObject();
        JsonObject bindings=object.has("bindings")?object(object.get("bindings"),path+".bindings"):new JsonObject();
        JsonObject expressions=object.has("expressions")?object(object.get("expressions"),path+".expressions"):new JsonObject();
        JsonObject templates=object.has("templates")?object(object.get("templates"),path+".templates"):new JsonObject();
        try{return ArgumentTemplate.compile(args,bindings,expressions,templates,types,path);}
        catch(ArgumentTemplate.Invalid invalid){throw new Invalid(invalid.field(),invalid.getMessage());}
        catch(BehaviorExpression.Invalid invalid){throw new Invalid(invalid.field(),invalid.getMessage());}
    }
    private static Value value(JsonElement element,Type type,String path){
        try{return BehaviorValues.read(element,type);}
        catch(RuntimeException invalid){throw new Invalid(path,"expected bounded "+BehaviorValues.name(type)+" value");}
    }
    private static JsonObject object(JsonElement element,String path){if(element==null||!element.isJsonObject())throw new Invalid(path,"expected object");return element.getAsJsonObject();}
    private static void keys(JsonObject object,String path,String owner,List<String> allowed){for(String key:object.keySet())if(!allowed.contains(key))throw new Invalid(path+"."+key,"unsupported field for "+owner+"; allowed: "+Suggestions.list(allowed)+Suggestions.didYouMean(key,allowed));}
    private static String schemaHint(String word,List<String> options){return Suggestions.didYouMean(word,options);}
    private static String text(JsonObject object,String key,String path){JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().isBlank())throw new Invalid(path+"."+key,"expected nonblank string");return value.getAsString();}
    private static int integer(JsonObject object,String key,String path,int minimum,int maximum){try{JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new ArithmeticException();int number=value.getAsBigDecimal().intValueExact();if(number<minimum||number>maximum)throw new ArithmeticException();return number;}catch(RuntimeException invalid){throw new Invalid(path+"."+key,"expected integer "+minimum+".."+maximum);}}
    private static String identifier(String value,String path){if(!value.matches("[a-z0-9][a-z0-9_-]{0,63}"))throw new Invalid(path,"expected a lowercase identifier within 64 characters");return value;}
    private static Capabilities.Key capability(String value,String path){String[] parts=value.split(":",-1);if(parts.length!=2)throw new Invalid(path,"expected namespace:name");try{return new Capabilities.Key(parts[0],parts[1]);}catch(IllegalArgumentException invalid){throw new Invalid(path,"expected namespace:name identifiers");}}
}
