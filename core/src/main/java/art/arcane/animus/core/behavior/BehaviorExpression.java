package art.arcane.animus.core.behavior;

import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import art.arcane.animus.core.gui.GuiExpression;
import java.util.*;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * A behavior expression: the Gloss grammar of {@link GuiExpression} with behavior roots, facts and functions. Compiled
 * once; each evaluation is bounded by {@link #MAX_VISITS} steps and {@link #MAX_TEXT} characters per intermediate string.
 */
public final class BehaviorExpression {
    public static final int MAX_LENGTH=4096,MAX_VISITS=4096,MAX_TEXT=65536;
    public static final List<String> ROOTS=List.of("player","npc","server","context");
    /** Declared names expressions can read. Other declared names are valid variables that expressions cannot name. */
    public static final Pattern NAME=Pattern.compile("[a-z][a-z0-9_]{0,63}");
    public static final Set<String> RESERVED=Set.of("player","npc","server","context","true","false");
    public static final List<String> PLAYER_FACTS=List.of("name","uuid","online","health","max_health","food","level","world","x","y","z","location","gamemode","op","sneaking","flying","item_in_hand","item_in_hand_amount");
    public static final List<String> NPC_FACTS=List.of("name","id","citizens_id","uuid","spawned","health","world","x","y","z","location");
    public static final List<String> SERVER_FACTS=List.of("online_players","players","max_players","tick","time_millis");
    public static final List<String> FLAG_SCOPES=List.of("player","npc","relationship","global");
    public static final List<String> FUNCTIONS=List.of("get","has","keys","values","put","remove","append","insert","index_of","first","last","slice","reverse","sort","sum","join","split","range",
            "trim","replace","starts_with","ends_with","num","int","truthy","same","same_world","text","random","random_decimal","chance","pick","flag","has_flag","quest",
            "players","player_info","player_flag","player_has_flag");
    /** The most players {@code players} returns, which bounds a fan-out over a player list. */
    public static final int MAX_PLAYERS=100;
    private static final Pattern PLAYER_ID=Pattern.compile("(?:p@)?([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    private static final Pattern NUMERIC=Pattern.compile("\\s*-?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][-+]?[0-9]+)?\\s*");
    private static final Map<String,GuiExpression.Function> LIBRARY=library();

    /** A compile-time problem in an expression or template. */
    public static final class Invalid extends IllegalArgumentException {
        private final String field;
        public Invalid(String field,String message){super(message);this.field=field;}
        public String field(){return field;}
    }
    /** An evaluation that could not produce a value. Carries no stack trace. */
    public static final class Failed extends RuntimeException {
        private final String code,field;
        public Failed(String code,String field,String message){super(message,null,false,false);this.code=code;this.field=field;}
        public String code(){return code;}
        public String field(){return field;}
    }
    /** What one evaluation reads. The engine answers it on its owner thread. */
    public interface Environment {
        /** The current value of a declared variable, or null when the name is not declared. */
        Value variable(String name);
        BehaviorFacts facts();
        Optional<UUID> player();
        UUID npc();
        /** Values of the starting event: Boolean, Long, Double, String, List of String or Map of String to String. */
        Map<String,Object> context();
        /** A flag of this behavior's namespace, empty when unset or expired. */
        Optional<Value> flag(String scope,String name);
        /** A quest stage of this behavior's namespace, zero when unset. */
        long quest(String scope,String name);
        RandomGenerator random();
        /** A player flag of this behavior's namespace for another player, empty when unset, expired or not readable here. */
        default Optional<Value> playerFlag(UUID player,String name){return Optional.empty();}
    }

    private final GuiExpression expression;private final String field;
    private BehaviorExpression(GuiExpression expression,String field){this.expression=expression;this.field=field;}

    /** Compiles against the declared variable names; every root and fact name is checked here. */
    public static BehaviorExpression compile(String source,Set<String> variables,String field){
        Set<String> roots=new HashSet<>(ROOTS);for(String name:variables)if(NAME.matcher(name).matches()&&!RESERVED.contains(name))roots.add(name);
        GuiExpression parsed;
        try{parsed=GuiExpression.parse(source,roots,MAX_LENGTH,LIBRARY);}
        catch(GuiExpression.Invalid invalid){throw new Invalid(field,invalid.getMessage());}
        for(String name:parsed.variables()){
            String[] parts=name.split("\\.",-1);
            List<String> facts=switch(parts[0]){case "player"->PLAYER_FACTS;case "npc"->NPC_FACTS;case "server"->SERVER_FACTS;default->null;};
            if(facts!=null&&(parts.length!=2||!facts.contains(parts[1])))throw new Invalid(field,"unknown fact '"+name+"'; "+parts[0]+" facts: "+String.join(", ",facts));
            if(parts[0].equals("context")&&parts.length<2)throw new Invalid(field,"context needs a key, as in context.message");
        }
        return new BehaviorExpression(parsed,field);
    }
    public String source(){return expression.source();}
    public String field(){return field;}

    /** The raw result: Boolean, Double, String, List or Map. Throws {@link Failed} with code behavior-expression-failed. */
    public Object evaluate(Environment environment){
        Evaluation evaluation=new Evaluation(environment,field);
        try{return expression.evaluate(evaluation,MAX_VISITS,MAX_TEXT);}
        catch(GuiExpression.Invalid invalid){throw new Failed("behavior-expression-failed",field,invalid.getMessage());}
        catch(ArithmeticException | IllegalArgumentException | IndexOutOfBoundsException invalid){throw new Failed("behavior-expression-failed",field,String.valueOf(invalid.getMessage()));}
    }

    /** Resolves dotted names and counts collection work against the same step budget as the grammar. */
    private static final class Evaluation implements GuiExpression.Scope {
        final Environment environment;final String field;int work;
        Evaluation(Environment environment,String field){this.environment=environment;this.field=field;}
        void charge(int amount){work+=Math.max(1,amount);if(work>MAX_VISITS)throw new GuiExpression.Invalid("exceeds "+MAX_VISITS+" evaluation steps");}
        @Override public Object variable(String name){
            String[] parts=name.split("\\.",-1);charge(parts.length);
            Object current=switch(parts[0]){
                case "player"->fact(environment.player().flatMap(environment.facts()::player),name,parts[1]);
                case "npc"->fact(environment.facts().npc(environment.npc()),name,parts[1]);
                case "server"->fact(Optional.of(environment.facts().server()),name,parts[1]);
                case "context"->{Object value=environment.context().get(parts[1]);if(value==null)throw new Failed("behavior-expression-failed",field,"fact-unavailable: "+parts[0]+"."+parts[1]);yield plain(value);}
                default->{Value value=environment.variable(parts[0]);if(value==null)throw new GuiExpression.Invalid("unknown variable '"+parts[0]+"'");yield BehaviorValues.expression(value);}
            };
            int start=parts[0].equals("context")?2:parts[0].equals("player")||parts[0].equals("npc")||parts[0].equals("server")?2:1;
            for(int i=start;i<parts.length;i++){
                if(!(current instanceof Map<?,?> map))throw new GuiExpression.Invalid("'"+String.join(".",Arrays.copyOf(parts,i))+"' is a "+BehaviorValues.describe(current)+", not a map");
                current=map.get(parts[i]);
                if(current==null)throw new GuiExpression.Invalid("no key '"+parts[i]+"' in '"+String.join(".",Arrays.copyOf(parts,i))+"'");
            }
            return current;
        }
        private Object fact(Optional<Map<String,Object>> snapshot,String name,String key){
            Object value=snapshot.map(facts->facts.get(key)).orElse(null);
            if(value==null)throw new Failed("behavior-expression-failed",field,"fact-unavailable: "+name);
            return plain(value);
        }
    }
    private static Object plain(Object value){
        return switch(value){
            case Number number->number.doubleValue();
            case List<?> items->{List<Object> copy=new ArrayList<>();for(Object item:items)copy.add(plain(item));yield Collections.unmodifiableList(copy);}
            case Map<?,?> entries->{LinkedHashMap<String,Object> copy=new LinkedHashMap<>();for(Map.Entry<?,?> entry:entries.entrySet())copy.put(String.valueOf(entry.getKey()),plain(entry.getValue()));yield Collections.unmodifiableMap(copy);}
            case Boolean flag->flag;
            default->value.toString();
        };
    }

    private static Map<String,GuiExpression.Function> library(){
        Map<String,GuiExpression.Function> functions=new HashMap<>();
        functions.put("get",(args,scope)->{arity("get",args,2,3);Object found=lookup(args.get(0),args.get(1));if(found!=null)return found;if(args.size()==3)return args.get(2);throw new GuiExpression.Invalid("get: no "+(args.get(0) instanceof List<?>?"index ":"key ")+GuiExpression.text(args.get(1)));});
        functions.put("has",(args,scope)->{arity("has",args,2,2);return map("has",args,0).containsKey(GuiExpression.text(args.get(1)));});
        functions.put("keys",(args,scope)->{arity("keys",args,1,1);Map<String,Object> map=map("keys",args,0);work(scope,map.size());return List.copyOf(map.keySet());});
        functions.put("values",(args,scope)->{arity("values",args,1,1);Map<String,Object> map=map("values",args,0);work(scope,map.size());return Collections.unmodifiableList(new ArrayList<>(map.values()));});
        functions.put("put",(args,scope)->{arity("put",args,3,3);Map<String,Object> copy=new LinkedHashMap<>(map("put",args,0));copy.put(key(args.get(1)),args.get(2));bound("put",copy.size());work(scope,copy.size());return Collections.unmodifiableMap(copy);});
        functions.put("remove",(args,scope)->{
            arity("remove",args,2,2);
            if(args.get(0) instanceof List<?> items){List<Object> copy=new ArrayList<>(items);int index=index("remove",args.get(1),copy.size()-1);copy.remove(index);work(scope,copy.size());return Collections.unmodifiableList(copy);}
            Map<String,Object> copy=new LinkedHashMap<>(map("remove",args,0));copy.remove(GuiExpression.text(args.get(1)));work(scope,copy.size());return Collections.unmodifiableMap(copy);
        });
        functions.put("append",(args,scope)->{arity("append",args,2,2);List<Object> copy=new ArrayList<>(list("append",args,0));copy.add(args.get(1));bound("append",copy.size());work(scope,copy.size());return Collections.unmodifiableList(copy);});
        functions.put("insert",(args,scope)->{arity("insert",args,3,3);List<Object> copy=new ArrayList<>(list("insert",args,0));copy.add(index("insert",args.get(1),copy.size()),args.get(2));bound("insert",copy.size());work(scope,copy.size());return Collections.unmodifiableList(copy);});
        functions.put("index_of",(args,scope)->{arity("index_of",args,2,2);List<Object> items=list("index_of",args,0);work(scope,items.size());return (double)items.indexOf(args.get(1));});
        functions.put("first",(args,scope)->{arity("first",args,1,1);List<Object> items=list("first",args,0);if(items.isEmpty())throw new GuiExpression.Invalid("first: the list is empty");return items.getFirst();});
        functions.put("last",(args,scope)->{arity("last",args,1,1);List<Object> items=list("last",args,0);if(items.isEmpty())throw new GuiExpression.Invalid("last: the list is empty");return items.getLast();});
        functions.put("slice",(args,scope)->{
            arity("slice",args,3,3);
            int size=args.get(0) instanceof List<?> items?items.size():GuiExpression.text(args.get(0)).length();
            int from=(int)Math.clamp(whole("slice",args.get(1)),0,size),to=(int)Math.clamp(whole("slice",args.get(2)),from,size);work(scope,to-from);
            if(args.get(0) instanceof List<?> items)return Collections.unmodifiableList(new ArrayList<>(items.subList(from,to)));
            return GuiExpression.text(args.get(0)).substring(from,to);
        });
        functions.put("reverse",(args,scope)->{
            arity("reverse",args,1,1);
            if(args.get(0) instanceof List<?> items){List<Object> copy=new ArrayList<>(items);Collections.reverse(copy);work(scope,copy.size());return Collections.unmodifiableList(copy);}
            String text=GuiExpression.text(args.get(0));work(scope,text.length()/64);return new StringBuilder(text).reverse().toString();
        });
        functions.put("sort",(args,scope)->{
            arity("sort",args,1,1);List<Object> copy=new ArrayList<>(list("sort",args,0));work(scope,copy.size()*8);
            if(copy.stream().allMatch(Double.class::isInstance))copy.sort(Comparator.comparingDouble(item->(Double)item));
            else if(copy.stream().allMatch(String.class::isInstance))copy.sort(Comparator.comparing(item->(String)item));
            else throw new GuiExpression.Invalid("sort: the list must hold only numbers or only text");
            return Collections.unmodifiableList(copy);
        });
        functions.put("sum",(args,scope)->{arity("sum",args,1,1);List<Object> items=list("sum",args,0);work(scope,items.size());double total=0;for(Object item:items){if(!(item instanceof Double number))throw new GuiExpression.Invalid("sum: every item must be a number");total+=number;}return total;});
        functions.put("join",(args,scope)->{
            arity("join",args,2,2);List<Object> items=list("join",args,0);String separator=GuiExpression.text(args.get(1));work(scope,items.size());
            StringBuilder out=new StringBuilder();
            for(int i=0;i<items.size();i++){if(i>0)out.append(separator);out.append(GuiExpression.text(items.get(i)));if(out.length()>MAX_TEXT)throw new GuiExpression.Invalid("text exceeds "+MAX_TEXT+" characters");}
            return out.toString();
        });
        functions.put("split",(args,scope)->{
            arity("split",args,2,2);String text=GuiExpression.text(args.get(0)),separator=GuiExpression.text(args.get(1));
            if(separator.isEmpty())throw new GuiExpression.Invalid("split: the separator is empty");
            String[] parts=text.split(Pattern.quote(separator),-1);bound("split",parts.length);work(scope,parts.length);return List.of((Object[])parts);
        });
        functions.put("range",(args,scope)->{
            arity("range",args,2,2);long from=whole("range",args.get(0)),to=whole("range",args.get(1));
            if(to>=from&&to-from>=BehaviorValues.MAX_ITEMS)throw new GuiExpression.Invalid("range: at most "+BehaviorValues.MAX_ITEMS+" items");
            List<Object> items=new ArrayList<>();for(long value=from;value<=to;value++)items.add((double)value);work(scope,items.size());return Collections.unmodifiableList(items);
        });
        functions.put("trim",(args,scope)->{arity("trim",args,1,1);return GuiExpression.text(args.get(0)).strip();});
        functions.put("replace",(args,scope)->{
            arity("replace",args,3,3);String text=GuiExpression.text(args.get(0)),find=GuiExpression.text(args.get(1)),with=GuiExpression.text(args.get(2));
            if(find.isEmpty())throw new GuiExpression.Invalid("replace: the text to find is empty");
            long count=(text.length()-text.replace(find,"").length())/find.length();
            if(text.length()+count*(with.length()-find.length())>MAX_TEXT)throw new GuiExpression.Invalid("text exceeds "+MAX_TEXT+" characters");
            return text.replace(find,with);
        });
        functions.put("starts_with",(args,scope)->{arity("starts_with",args,2,2);return GuiExpression.text(args.get(0)).startsWith(GuiExpression.text(args.get(1)));});
        functions.put("ends_with",(args,scope)->{arity("ends_with",args,2,2);return GuiExpression.text(args.get(0)).endsWith(GuiExpression.text(args.get(1)));});
        functions.put("num",(args,scope)->{arity("num",args,1,1);return number(args.get(0));});
        functions.put("int",(args,scope)->{arity("int",args,1,1);double value=number(args.get(0));return value<0?Math.ceil(value):Math.floor(value);});
        functions.put("truthy",(args,scope)->{arity("truthy",args,1,1);Object value=args.get(0);return value instanceof Boolean flag?flag:value instanceof Double number?number!=0:value instanceof String text&&text.equalsIgnoreCase("true");});
        functions.put("same",(args,scope)->{arity("same",args,2,2);return same(args.get(0),args.get(1));});
        functions.put("same_world",(args,scope)->{arity("same_world",args,2,2);BehaviorFacts facts=environment(scope).facts();return world(facts,args.get(0)).equalsIgnoreCase(world(facts,args.get(1)));});
        functions.put("text",(args,scope)->{arity("text",args,1,1);return GuiExpression.text(args.get(0));});
        functions.put("random",(args,scope)->{
            arity("random",args,2,2);long minimum=whole("random",args.get(0)),maximum=whole("random",args.get(1));
            if(maximum<minimum)throw new GuiExpression.Invalid("random: the maximum is below the minimum");
            return (double)(maximum==minimum?minimum:random(scope).nextLong(minimum,maximum+1));
        });
        functions.put("random_decimal",(args,scope)->{arity("random_decimal",args,0,0);return random(scope).nextDouble();});
        functions.put("chance",(args,scope)->{arity("chance",args,1,1);double percent=number(args.get(0));return random(scope).nextDouble()*100<percent;});
        functions.put("pick",(args,scope)->{arity("pick",args,1,1);List<Object> items=list("pick",args,0);if(items.isEmpty())throw new GuiExpression.Invalid("pick: the list is empty");return items.get(random(scope).nextInt(items.size()));});
        functions.put("flag",(args,scope)->{
            arity("flag",args,2,3);Optional<Value> value=environment(scope).flag(flagScope(args.get(0)),GuiExpression.text(args.get(1)));
            if(value.isPresent())return BehaviorValues.expression(value.get());
            if(args.size()==3)return args.get(2);
            throw new GuiExpression.Invalid("flag '"+GuiExpression.text(args.get(1))+"' is not set");
        });
        functions.put("has_flag",(args,scope)->{arity("has_flag",args,2,2);return environment(scope).flag(flagScope(args.get(0)),GuiExpression.text(args.get(1))).isPresent();});
        functions.put("quest",(args,scope)->{arity("quest",args,2,2);return (double)environment(scope).quest(flagScope(args.get(0)),GuiExpression.text(args.get(1)));});
        functions.put("players",(args,scope)->{
            arity("players",args,1,1);List<Object> found=new ArrayList<>();
            for(Object item:args.get(0) instanceof List<?> items?items:List.of(args.get(0))){
                Optional<UUID> player=playerId(item);
                if(player.isPresent()&&!found.contains(player.get().toString()))found.add(player.get().toString());
                if(found.size()>=MAX_PLAYERS)break;
            }
            work(scope,found.size());return Collections.unmodifiableList(found);
        });
        functions.put("player_info",(args,scope)->{
            arity("player_info",args,1,1);Optional<UUID> player=playerId(args.get(0));
            Optional<Map<String,Object>> facts=player.flatMap(environment(scope).facts()::player);
            return facts.map(BehaviorExpression::plain).orElse(Map.of());
        });
        functions.put("player_flag",(args,scope)->{
            arity("player_flag",args,2,3);Optional<UUID> player=playerId(args.get(0));
            Optional<Value> value=player.flatMap(id->environment(scope).playerFlag(id,GuiExpression.text(args.get(1))));
            if(value.isPresent())return BehaviorValues.expression(value.get());
            if(args.size()==3)return args.get(2);
            throw new GuiExpression.Invalid("flag '"+GuiExpression.text(args.get(1))+"' is not set for that player");
        });
        functions.put("player_has_flag",(args,scope)->{arity("player_has_flag",args,2,2);return playerId(args.get(0)).flatMap(id->environment(scope).playerFlag(id,GuiExpression.text(args.get(1)))).isPresent();});
        for(GuiExpression.Function function:List.copyOf(functions.values()))Objects.requireNonNull(function);
        if(!functions.keySet().equals(Set.copyOf(FUNCTIONS)))throw new IllegalStateException("Behavior function table and list differ");
        return Map.copyOf(functions);
    }
    private static void arity(String name,List<Object> args,int minimum,int maximum){
        if(args.size()<minimum||args.size()>maximum)throw new GuiExpression.Invalid(name+" takes "+(minimum==maximum?String.valueOf(minimum):minimum+" or "+maximum)+" arguments, got "+args.size());
    }
    private static Environment environment(GuiExpression.Scope scope){return ((Evaluation)scope).environment;}
    /** A player UUID, also written as Denizen's {@code p@<uuid>}; empty for anything else. */
    private static Optional<UUID> playerId(Object value){
        if(!(value instanceof String text))return Optional.empty();
        java.util.regex.Matcher matcher=PLAYER_ID.matcher(text.strip());
        return matcher.matches()?Optional.of(UUID.fromString(matcher.group(1).toLowerCase(Locale.ROOT))):Optional.empty();
    }
    private static RandomGenerator random(GuiExpression.Scope scope){return environment(scope).random();}
    private static void work(GuiExpression.Scope scope,int amount){((Evaluation)scope).charge(amount);}
    private static String flagScope(Object value){
        String scope=GuiExpression.text(value);
        if(!FLAG_SCOPES.contains(scope))throw new GuiExpression.Invalid("flag scope must be one of "+String.join(", ",FLAG_SCOPES)+", got '"+scope+"'");
        return scope;
    }
    private static Object lookup(Object container,Object key){
        if(container instanceof List<?> items){long index=whole("get",key);return index>=0&&index<items.size()?items.get((int)index):null;}
        if(container instanceof Map<?,?> entries)return entries.get(GuiExpression.text(key));
        throw new GuiExpression.Invalid("get: expected a list or map, got "+BehaviorValues.describe(container));
    }
    @SuppressWarnings("unchecked")
    private static List<Object> list(String name,List<Object> args,int index){if(args.get(index) instanceof List<?> items)return (List<Object>)items;throw new GuiExpression.Invalid(name+": argument "+(index+1)+" must be a list, got "+BehaviorValues.describe(args.get(index)));}
    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(String name,List<Object> args,int index){if(args.get(index) instanceof Map<?,?> entries)return (Map<String,Object>)entries;throw new GuiExpression.Invalid(name+": argument "+(index+1)+" must be a map, got "+BehaviorValues.describe(args.get(index)));}
    private static String key(Object value){String key=GuiExpression.text(value);if(key.isEmpty()||key.length()>BehaviorValues.MAX_KEY_LENGTH)throw new GuiExpression.Invalid("map keys are 1 to "+BehaviorValues.MAX_KEY_LENGTH+" characters");return key;}
    private static void bound(String name,int size){if(size>BehaviorValues.MAX_ITEMS)throw new GuiExpression.Invalid(name+": at most "+BehaviorValues.MAX_ITEMS+" items");}
    private static int index(String name,Object value,int maximum){long index=whole(name,value);if(index<0||index>maximum)throw new GuiExpression.Invalid(name+": index "+index+" is outside 0.."+maximum);return (int)index;}
    private static long whole(String name,Object value){
        if(value instanceof Double number&&number==Math.rint(number)&&Math.abs(number)<=BehaviorValues.SAFE_WHOLE)return (long)(double)number;
        throw new GuiExpression.Invalid(name+": expected a whole number, got "+GuiExpression.text(value));
    }
    private static double number(Object value){
        if(value instanceof Double number)return number;
        if(value instanceof String text&&NUMERIC.matcher(text).matches())return Double.parseDouble(text.strip());
        throw new GuiExpression.Invalid("'"+GuiExpression.text(value)+"' is not a number");
    }
    private static boolean numeric(Object value){return value instanceof Double||value instanceof String text&&NUMERIC.matcher(text).matches();}
    /** A world name, dimension key or UUID in the form the platform's world facts use; a world it does not know stays as written. */
    private static String world(BehaviorFacts facts,Object reference){String text=GuiExpression.text(reference);return facts.world(text).orElse(text);}
    /** Denizen equality: numerically when both sides are numbers or numeric text, otherwise case-insensitive text. */
    static boolean same(Object left,Object right){
        if(numeric(left)&&numeric(right))return number(left)==number(right);
        return GuiExpression.text(left).equalsIgnoreCase(GuiExpression.text(right));
    }
}
