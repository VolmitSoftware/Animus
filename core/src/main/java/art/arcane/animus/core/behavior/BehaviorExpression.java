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
    public static final List<String> NPC_FACTS=List.of("name","id","citizens_id","uuid","spawned","health","world","x","y","z","location","behaviors");
    public static final List<String> SERVER_FACTS=List.of("online_players","players","max_players","tick","time_millis");
    public static final List<String> FLAG_SCOPES=List.of("player","npc","relationship","global");
    public static final List<String> FUNCTIONS=List.of("get","has","keys","values","put","remove","append","insert","index_of","first","last","slice","reverse","sort","sum","join","split","range",
            "trim","replace","starts_with","ends_with","num","int","truthy","same","same_world","text","random","random_decimal","chance","pick","flag","has_flag","quest",
            "players","player_info","player_flag","player_has_flag","npcs","npc_info","npc_flag","npc_has_flag","distance","duration_seconds","duration_text","flag_expiry","player_flag_expiry","type_of",
            "has_player","offset","players_near","within","now","player_named");
    /** The most players {@code players} returns, which bounds a fan-out over a player list. */
    public static final int MAX_PLAYERS=100;
    /** The most NPC UUIDs {@code npcs} returns. */
    public static final int MAX_NPCS=1024;
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
        /** An NPC flag of this behavior's namespace for another NPC, empty when unset, expired or not readable here. */
        default Optional<Value> npcFlag(UUID npc,String name){return Optional.empty();}
        /** When a flag of this behavior's namespace expires, in epoch milliseconds; empty when it never does or is unset. */
        default OptionalLong flagExpiry(String scope,String name){return OptionalLong.empty();}
        /** As {@link #flagExpiry} for a player flag of another player. */
        default OptionalLong playerFlagExpiry(UUID player,String name){return OptionalLong.empty();}
        /** The time now in epoch milliseconds, read as the expression runs rather than from the server facts snapshot. */
        default long nowMillis(){return System.currentTimeMillis();}
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
        functions.put("npcs",(args,scope)->{
            arity("npcs",args,0,0);List<Object> found=new ArrayList<>();
            for(UUID npc:environment(scope).facts().npcs()){if(found.size()>=MAX_NPCS)break;found.add(npc.toString());}
            work(scope,found.size());return Collections.unmodifiableList(found);
        });
        functions.put("npc_info",(args,scope)->{
            arity("npc_info",args,1,1);Optional<UUID> npc=npcId(args.get(0));
            return npc.flatMap(environment(scope).facts()::npc).map(BehaviorExpression::plain).orElse(Map.of());
        });
        functions.put("npc_flag",(args,scope)->{
            arity("npc_flag",args,2,3);Optional<Value> value=npcId(args.get(0)).flatMap(id->environment(scope).npcFlag(id,GuiExpression.text(args.get(1))));
            if(value.isPresent())return BehaviorValues.expression(value.get());
            if(args.size()==3)return args.get(2);
            throw new GuiExpression.Invalid("flag '"+GuiExpression.text(args.get(1))+"' is not set for that NPC");
        });
        functions.put("distance",(args,scope)->{
            arity("distance",args,2,2);String[] from=location("distance",args.get(0)),to=location("distance",args.get(1));
            if(from.length>3&&to.length>3&&!same(from[3].strip(),to[3].strip()))return 1.0E9;
            double dx=number(from[0])-number(to[0]),dy=number(from[1])-number(to[1]),dz=number(from[2])-number(to[2]);
            return Math.sqrt(dx*dx+dy*dy+dz*dz);
        });
        functions.put("now",(args,scope)->{arity("now",args,0,0);return (double)environment(scope).nowMillis();});
        functions.put("player_named",(args,scope)->{
            arity("player_named",args,1,1);String wanted=GuiExpression.text(args.get(0)).strip().toLowerCase(Locale.ROOT);
            if(wanted.isEmpty())return "";
            BehaviorFacts facts=environment(scope).facts();Object listed=facts.server().get("players");
            String exact="",starts="",contains="";
            if(listed instanceof List<?> players)for(Object entry:players){
                Optional<UUID> player=playerId(entry);if(player.isEmpty())continue;
                Optional<Map<String,Object>> known=facts.player(player.get());if(known.isEmpty()||!(known.get().get("name") instanceof String name))continue;
                String lowered=name.toLowerCase(Locale.ROOT),id=player.get().toString();
                if(lowered.equals(wanted)&&exact.isEmpty())exact=id;
                else if(lowered.startsWith(wanted))starts=better(facts,starts,id);
                else if(lowered.contains(wanted))contains=better(facts,contains,id);
            }
            return !exact.isEmpty()?exact:!starts.isEmpty()?starts:contains;
        });
        functions.put("has_player",(args,scope)->{arity("has_player",args,0,0);return environment(scope).player().isPresent();});
        functions.put("offset",(args,scope)->{
            arity("offset",args,4,4);String[] at=location("offset",args.get(0));
            double x=number(at[0])+number(args.get(1)),y=number(at[1])+number(args.get(2)),z=number(at[2])+number(args.get(3));
            return String.format(Locale.ROOT,"%.2f,%.2f,%.2f",x,y,z)+(at.length>3?","+at[3].strip():"");
        });
        functions.put("players_near",(args,scope)->{
            arity("players_near",args,2,2);String[] at=location("players_near",args.get(0));double radius=number(args.get(1));
            BehaviorFacts facts=environment(scope).facts();
            Object listed=facts.server().get("players");
            List<Map.Entry<Double,String>> found=new ArrayList<>();
            if(listed instanceof List<?> players)for(Object entry:players){
                Optional<UUID> player=playerId(entry);if(player.isEmpty())continue;
                Optional<Map<String,Object>> known=facts.player(player.get());if(known.isEmpty()||!(known.get().get("location") instanceof String place))continue;
                String[] other=place.split(",",-1);if(other.length<3||!numeric(other[0])||!numeric(other[1])||!numeric(other[2]))continue;
                if(at.length>3&&other.length>3&&!world(facts,at[3].strip()).equalsIgnoreCase(world(facts,other[3].strip())))continue;
                double dx=number(other[0])-number(at[0]),dy=number(other[1])-number(at[1]),dz=number(other[2])-number(at[2]),distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
                if(distance<=radius)found.add(Map.entry(distance,player.get().toString()));
            }
            found.sort(Map.Entry.<Double,String>comparingByKey().thenComparing(Map.Entry.comparingByValue()));
            work(scope,found.size());List<Object> near=new ArrayList<>();for(Map.Entry<Double,String> entry:found){if(near.size()>=MAX_PLAYERS)break;near.add(entry.getValue());}
            return Collections.unmodifiableList(near);
        });
        functions.put("within",(args,scope)->{
            arity("within",args,2,2);String[] at=location("within",args.get(0));
            if(!(args.get(1) instanceof Map<?,?> area)||!(area.get("bounds") instanceof List<?> bounds)||bounds.size()!=6||!(area.get("world") instanceof String world))
                throw new GuiExpression.Invalid("within: expected an area map with world, shape and six bounds");
            if(at.length>3&&!world(environment(scope).facts(),at[3].strip()).equalsIgnoreCase(world(environment(scope).facts(),world)))return false;
            double x=number(at[0]),y=number(at[1]),z=number(at[2]);double[] b=new double[6];for(int i=0;i<6;i++)b[i]=number(bounds.get(i));
            if("ellipsoid".equals(area.get("shape"))){double dx=(x-b[0])/b[3],dy=(y-b[1])/b[4],dz=(z-b[2])/b[5];return dx*dx+dy*dy+dz*dz<=1;}
            // A cuboid holds the whole blocks from one corner block to the other, as Denizen's does.
            return x>=Math.floor(Math.min(b[0],b[3]))&&x<Math.floor(Math.max(b[0],b[3]))+1&&y>=Math.floor(Math.min(b[1],b[4]))&&y<Math.floor(Math.max(b[1],b[4]))+1
                    &&z>=Math.floor(Math.min(b[2],b[5]))&&z<Math.floor(Math.max(b[2],b[5]))+1;
        });
        functions.put("type_of",(args,scope)->{arity("type_of",args,1,1);return switch(args.get(0)){case List<?> ignored->"list";case Map<?,?> ignored->"map";case Boolean ignored->"boolean";case Number ignored->"number";default->"text";};});
        functions.put("duration_seconds",(args,scope)->{arity("duration_seconds",args,1,1);return durationSeconds(args.get(0));});
        functions.put("duration_text",(args,scope)->{arity("duration_text",args,1,1);return durationText(number(args.get(0)));});
        functions.put("flag_expiry",(args,scope)->{arity("flag_expiry",args,2,2);return (double)environment(scope).flagExpiry(flagScope(args.get(0)),GuiExpression.text(args.get(1))).orElse(0);});
        functions.put("player_flag_expiry",(args,scope)->{arity("player_flag_expiry",args,2,2);
            return (double)playerId(args.get(0)).map(id->environment(scope).playerFlagExpiry(id,GuiExpression.text(args.get(1))).orElse(0)).orElse(0L);});
        functions.put("npc_has_flag",(args,scope)->{arity("npc_has_flag",args,2,2);return npcId(args.get(0)).flatMap(id->environment(scope).npcFlag(id,GuiExpression.text(args.get(1)))).isPresent();});
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
    private static final Pattern DURATION_PART=Pattern.compile("([0-9]+(?:\\.[0-9]+)?)([tsmhdwy])");
    private static final Map<Character,Double> DURATION_UNITS=Map.of('t',0.05,'s',1.0,'m',60.0,'h',3600.0,'d',86400.0,'w',604800.0,'y',31536000.0);
    /** Seconds in a Denizen duration: a number of seconds, or text such as {@code 10m}, {@code 30t} or {@code d@1d1h}. */
    private static double durationSeconds(Object value){
        if(numeric(value))return number(value);
        String text=GuiExpression.text(value).strip().toLowerCase(Locale.ROOT);
        if(text.startsWith("d@"))text=text.substring(2);
        java.util.regex.Matcher part=DURATION_PART.matcher(text);
        double total=0;int at=0;
        while(at<text.length()&&part.find(at)&&part.start()==at){total+=Double.parseDouble(part.group(1))*DURATION_UNITS.get(part.group(2).charAt(0));at=part.end();}
        if(at==0||at!=text.length())throw new GuiExpression.Invalid("'"+GuiExpression.text(value)+"' is not a duration");
        return total;
    }
    /** A duration as Denizen writes it readably: days and hours, minutes under a day, seconds under ten minutes. */
    private static String durationText(double seconds){
        long total=(long)Math.max(0,Math.floor(seconds)),days=total/86400,hours=total%86400/3600,minutes=total%3600/60,rest=total%60;
        List<String> parts=new ArrayList<>();
        if(days>0)parts.add(days+"d");
        if(hours>0)parts.add(hours+"h");
        if(minutes>0&&days==0)parts.add(minutes+"m");
        if(rest>0&&total<600)parts.add(rest+"s");
        return parts.isEmpty()?"0s":String.join(" ",parts);
    }
    /** A location written {@code x,y,z} or {@code x,y,z,world}, as the location facts give it, split at its commas. */
    private static String[] location(String name,Object value){
        String[] parts=GuiExpression.text(value).split(",",-1);
        if(parts.length<3||parts.length>4||!numeric(parts[0])||!numeric(parts[1])||!numeric(parts[2]))throw new GuiExpression.Invalid(name+": expected a location x,y,z,world, got "+GuiExpression.text(value));
        return parts;
    }
    /** An NPC UUID, also written as {@code n@<uuid>}; empty for anything else. */
    private static Optional<UUID> npcId(Object value){
        if(!(value instanceof String text))return Optional.empty();
        String bare=text.strip();if(bare.startsWith("n@"))bare=bare.substring(2);
        try{UUID id=UUID.fromString(bare);return id.toString().equalsIgnoreCase(bare)?Optional.of(id):Optional.empty();}
        catch(IllegalArgumentException invalid){return Optional.empty();}
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
    /** Of two matching players, the one with the shorter name, then the name first in order, as Denizen's match_player prefers. */
    private static String better(BehaviorFacts facts,String held,String candidate){
        if(held.isEmpty())return candidate;
        String a=name(facts,held),b=name(facts,candidate);
        return b.length()<a.length()||b.length()==a.length()&&b.compareToIgnoreCase(a)<0?candidate:held;
    }
    private static String name(BehaviorFacts facts,String player){return facts.player(UUID.fromString(player)).map(known->String.valueOf(known.get("name"))).orElse("");}
    private static String world(BehaviorFacts facts,Object reference){String text=GuiExpression.text(reference);return facts.world(text).orElse(text);}
    /** Denizen equality: numerically when both sides are numbers or numeric text, otherwise case-insensitive text. */
    static boolean same(Object left,Object right){
        if(numeric(left)&&numeric(right))return number(left)==number(right);
        return GuiExpression.text(left).equalsIgnoreCase(GuiExpression.text(right));
    }
}
