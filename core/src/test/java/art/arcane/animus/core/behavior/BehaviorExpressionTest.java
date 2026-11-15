package art.arcane.animus.core.behavior;

import art.arcane.animus.core.behavior.BehaviorProgram.Type;
import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import java.util.*;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorExpressionTest {
    static final UUID PLAYER=UUID.fromString("00000000-0000-0000-0000-0000000000a1"),NPC=UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final class Env implements BehaviorExpression.Environment {
        final Map<String,Value> variables=new HashMap<>();final Map<String,Value> flags=new HashMap<>();final Map<String,Long> quests=new HashMap<>();
        Map<String,Object> context=Map.of();Optional<UUID> player=Optional.of(PLAYER);RandomGenerator random=new Random(7);
        BehaviorFacts facts=new BehaviorFacts(){
            public Optional<Map<String,Object>> player(UUID id){return id.equals(PLAYER)?Optional.of(Map.of("name","Steve","health",18.5,"level",3L,"op",false)):Optional.empty();}
            public Optional<Map<String,Object>> npc(UUID id){return Optional.of(Map.of("name","Guide","spawned",true));}
            public Map<String,Object> server(){return Map.of("online_players",4L);}
        };
        public Value variable(String name){return variables.get(name);}
        public BehaviorFacts facts(){return facts;}
        public Optional<UUID> player(){return player;}
        public UUID npc(){return NPC;}
        public Map<String,Object> context(){return context;}
        public Optional<Value> flag(String scope,String name){return Optional.ofNullable(flags.get(scope+"/"+name));}
        public long quest(String scope,String name){return quests.getOrDefault(scope+"/"+name,0L);}
        public RandomGenerator random(){return random;}
        final Map<String,Value> npcFlags=new HashMap<>();
        @Override public Optional<Value> npcFlag(UUID npc,String name){return Optional.ofNullable(npcFlags.get(npc+"/"+name));}
        final Map<String,Long> expiries=new HashMap<>();
        @Override public OptionalLong flagExpiry(String scope,String name){Long at=expiries.get(scope+"/"+name);return at==null?OptionalLong.empty():OptionalLong.of(at);}
        @Override public OptionalLong playerFlagExpiry(UUID player,String name){Long at=expiries.get(player+"/"+name);return at==null?OptionalLong.empty():OptionalLong.of(at);}
    }
    static Object eval(String source,Env env){return BehaviorExpression.compile(source,env.variables.keySet(),"$.f").evaluate(env);}
    static Object eval(String source){return eval(source,new Env());}
    static BehaviorExpression.Failed failure(String source,Env env){return assertThrows(BehaviorExpression.Failed.class,()->eval(source,env));}
    static Value integer(long value){return new Value(Type.INTEGER,value);}
    static Value text(String value){return new Value(Type.STRING,value);}

    @Test void rootsDottedMapsAndUnknownRootsAreCheckedAtCompile(){
        Env env=new Env();env.variables.put("item",new Value(Type.MAP,Map.of("name",text("Sword"),"stats",new Value(Type.MAP,Map.of("dmg",integer(7))))));env.variables.put("count",integer(2));
        assertEquals("Sword",eval("item.name",env));assertEquals(14.0,eval("item.stats.dmg * count",env));
        assertTrue(failure("item.missing",env).getMessage().contains("missing"));
        assertThrows(BehaviorExpression.Invalid.class,()->BehaviorExpression.compile("ghost + 1",Set.of("count"),"$.f"));
        assertThrows(BehaviorExpression.Invalid.class,()->BehaviorExpression.compile("player.shoe_size",Set.of(),"$.f"));
        assertThrows(BehaviorExpression.Invalid.class,()->BehaviorExpression.compile("player",Set.of(),"$.f"));
        assertThrows(BehaviorExpression.Invalid.class,()->BehaviorExpression.compile("x".repeat(4097),Set.of(),"$.f"));
        assertDoesNotThrow(()->BehaviorExpression.compile("1"+" + 1".repeat(600),Set.of(),"$.f"));
    }
    @Test void factsAndContextReadSnapshotsAndMissingFactsFail(){
        Env env=new Env();env.context=Map.of("message","hi there","args",List.of("a","b"),"first",true);
        assertEquals("Steve has 18.5",eval("player.name + ' has ' + player.health",env));assertEquals(3.0,eval("player.level",env));
        assertEquals("Guide",eval("npc.name",env));assertEquals(4.0,eval("server.online_players",env));
        assertEquals("b",eval("get(context.args, 1)",env));assertEquals(true,eval("context.first",env));
        assertTrue(failure("player.world",env).getMessage().startsWith("fact-unavailable"));
        assertTrue(failure("context.missing",env).getMessage().startsWith("fact-unavailable"));
        env.player=Optional.empty();assertTrue(failure("player.name",env).getMessage().startsWith("fact-unavailable"));
    }
    @Test void sameWorldComparesWorldsThePlatformResolves(){
        Env env=new Env();
        BehaviorFacts base=env.facts;
        env.facts=new BehaviorFacts(){
            public Optional<Map<String,Object>> player(UUID id){return Optional.of(Map.of("world","world_nether"));}
            public Optional<Map<String,Object>> npc(UUID id){return base.npc(id);}
            public Map<String,Object> server(){return base.server();}
            public Optional<String> world(String reference){return switch(reference){
                case "world","minecraft:overworld"->Optional.of("world");
                case "world_nether","minecraft:the_nether"->Optional.of("world_nether");
                default->Optional.empty();
            };}
        };
        assertEquals(true,eval("same_world(player.world, 'minecraft:the_nether')",env));
        assertEquals(false,eval("same_world(player.world, 'minecraft:overworld')",env));
        assertEquals(false,eval("same_world('skylands', 'minecraft:overworld')",env));
        assertEquals(true,eval("same_world('Skylands', 'skylands')",env),"worlds no platform knows compare as text, ignoring case");
        assertEquals(true,eval("same_world('world', 'world')"),"without a resolver both sides compare as written");
        assertTrue(failure("same_world('world')",new Env()).getMessage().contains("same_world takes 2"));
    }
    @Test void collectionFunctionsAreImmutableAndBounded(){
        Env env=new Env();env.variables.put("names",new Value(Type.LIST,List.of(text("b"),text("a"))));
        assertEquals(List.of("b","a","c"),eval("append(names, 'c')",env));assertEquals(List.of("b","a"),BehaviorValues.expression(env.variables.get("names")));
        assertEquals(List.of("a","b"),eval("sort(names)",env));assertEquals(List.of("z","b","a"),eval("insert(names, 0, 'z')",env));
        assertEquals(1.0,eval("index_of(names, 'a')",env));assertEquals(-1.0,eval("index_of(names, 'q')",env));
        assertEquals("b",eval("first(names)",env));assertEquals("a",eval("last(names)",env));assertEquals(List.of("a"),eval("remove(names, 0)",env));
        env.variables.put("stats",new Value(Type.MAP,new LinkedHashMap<>(Map.of("hp",integer(5)))));
        assertEquals(Map.of("hp",5.0,"mp",2.0),eval("put(stats, 'mp', 2)",env));assertEquals(List.of("hp"),eval("keys(stats)",env));assertEquals(List.of(5.0),eval("values(stats)",env));
        assertEquals(true,eval("has(stats, 'hp')",env));assertEquals(9.0,eval("get(stats, 'xp', 9)",env));assertTrue(failure("get(stats, 'xp')",env).getMessage().contains("xp"));
        assertEquals(Map.of(),eval("remove(stats, 'hp')",env));
        assertEquals(List.of(1.0,2.0,3.0),eval("range(1, 3)"));assertTrue(failure("range(1, 300)",env).getMessage().contains("256"));
        assertEquals(6.0,eval("sum(range(1, 3))"));assertEquals("1-2-3",eval("join(range(1, 3), '-')"));assertEquals(List.of("a","b",""),eval("split('a,b,', ',')"));
        assertEquals(List.of(2.0,3.0),eval("slice(range(1, 4), 1, 3)"));assertEquals("ell",eval("slice('hello', 1, 4)"));assertEquals("olleh",eval("reverse('hello')"));
    }
    @Test void textNumberAndEqualityFunctionsFollowDenizenRules(){
        assertEquals("hi",eval("trim('  hi ')"));assertEquals("a-b",eval("replace('a b', ' ', '-')"));assertEquals(true,eval("starts_with('hello', 'he')"));assertEquals(true,eval("ends_with('hello', 'lo')"));
        assertEquals(12.5,eval("num('12.5')"));assertEquals(-3.0,eval("int(-3.7)"));assertEquals(3.0,eval("int('3.9')"));assertTrue(failure("num('abc')",new Env()).getMessage().contains("abc"));
        assertEquals(true,eval("truthy('TRUE')"));assertEquals(true,eval("truthy(2)"));assertEquals(false,eval("truthy('yes')"));assertEquals(false,eval("truthy(0)"));
        assertEquals(true,eval("same('5', 5.0)"));assertEquals(true,eval("same('Steve', 'steve')"));assertEquals(false,eval("same('a', 'b')"));
        assertEquals("3",eval("text(3)"));assertEquals("2.5",eval("text(2.5)"));
    }
    @Test void randomFunctionsUseTheInjectedGenerator(){
        Env first=new Env(),second=new Env();
        List<Object> a=List.of(eval("random(1, 6)",first),eval("random_decimal()",first),eval("chance(50)",first),eval("pick(['x','y','z'])",first));
        List<Object> b=List.of(eval("random(1, 6)",second),eval("random_decimal()",second),eval("chance(50)",second),eval("pick(['x','y','z'])",second));
        assertEquals(a,b);double roll=(Double)a.get(0);assertTrue(roll>=1&&roll<=6&&roll==Math.rint(roll));
        assertEquals(false,eval("chance(0)"));assertEquals(true,eval("chance(100)"));
    }
    @Test void flagsAndQuestsReadThroughTheEnvironment(){
        Env env=new Env();env.flags.put("player/met",new Value(Type.BOOLEAN,true));env.flags.put("global/count",integer(4));env.quests.put("relationship/intro",2L);
        assertEquals(true,eval("flag('player', 'met')",env));assertEquals(5.0,eval("flag('global', 'count') + 1",env));
        assertEquals("none",eval("flag('npc', 'mood', 'none')",env));assertTrue(failure("flag('npc', 'mood')",env).getMessage().contains("mood"));
        assertEquals(true,eval("has_flag('player', 'met')",env));assertEquals(false,eval("has_flag('npc', 'met')",env));
        assertEquals(2.0,eval("quest('relationship', 'intro')",env));assertEquals(0.0,eval("quest('player', 'other')",env));
        assertTrue(failure("flag('world', 'x')",env).getMessage().contains("scope"));
    }
    @Test void visitAndTextBudgetsEndTheEvaluation(){
        String heavy="len(range(1, 256))"+" + len(range(1, 256))".repeat(16);
        assertTrue(failure(heavy,new Env()).getMessage().contains("4096"));
        assertEquals(2048.0,eval("len(range(1, 256))"+" + len(range(1, 256))".repeat(7)));
        Env env=new Env();env.variables.put("chunk",text("x".repeat(4000)));
        String grow="chunk"+" + chunk".repeat(17);
        assertTrue(failure(grow,env).getMessage().contains("65536"));
        assertDoesNotThrow(()->eval("chunk + chunk",env));
    }
    @Test void templatesJoinHolesAsTextAndEscapeBraces(){
        Env env=new Env();env.variables.put("count",integer(3));
        BehaviorTemplate template=BehaviorTemplate.compile("Hi {{ player.name }}, {{ count * 2 }} left {{ '{{' }}x}}",env.variables.keySet(),"$.t");
        assertEquals("Hi Steve, 6 left {{x}}",template.evaluate(env));
        assertEquals("a }} b",BehaviorTemplate.compile("a {{ '}}' }} b",Set.of(),"$.t").evaluate(env));
        assertThrows(BehaviorExpression.Invalid.class,()->BehaviorTemplate.compile("open {{ 1 + 1",Set.of(),"$.t"));
        assertThrows(BehaviorExpression.Invalid.class,()->BehaviorTemplate.compile("bad {{ ghost }}",Set.of(),"$.t"));
    }
    @Test void coercionFollowsTheTargetType(){
        assertEquals(integer(4),BehaviorValues.coerce(4.0,Type.INTEGER));assertThrows(IllegalArgumentException.class,()->BehaviorValues.coerce(4.5,Type.INTEGER));
        assertThrows(IllegalArgumentException.class,()->BehaviorValues.coerce(1e300,Type.INTEGER));
        assertEquals(text("4"),BehaviorValues.coerce(4.0,Type.STRING));assertEquals(new Value(Type.DECIMAL,4.0),BehaviorValues.coerce(4.0,Type.DECIMAL));
        assertEquals(integer(4),BehaviorValues.coerce(4.0,Type.ANY));assertEquals(new Value(Type.DECIMAL,4.5),BehaviorValues.coerce(4.5,Type.ANY));
        assertThrows(IllegalArgumentException.class,()->BehaviorValues.coerce("true",Type.BOOLEAN));
        assertEquals(Type.LIST,BehaviorValues.coerce(List.of(1.0),Type.LIST).type());assertThrows(IllegalArgumentException.class,()->BehaviorValues.coerce(List.of(1.0),Type.MAP));
    }
    @Test void playerListsInfoAndOtherPlayersFlagsAreBounded(){
        Env env=new Env();
        UUID other=UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        env.variables.put("crowd",new Value(Type.LIST,List.of(text("p@"+PLAYER),text(other.toString()),text("not a player"),text(PLAYER.toString()))));
        assertEquals(List.of(PLAYER.toString(),other.toString()),eval("players(crowd)",env));
        assertEquals(List.of(PLAYER.toString()),eval("players('"+PLAYER+"')",env));
        List<Value> many=new ArrayList<>();for(int i=0;i<103;i++)many.add(text(new UUID(1,i).toString()));
        env.variables.put("many",new Value(Type.LIST,many));
        assertEquals(100.0,eval("len(players(many))",env));
        assertEquals("Steve",eval("get(player_info('p@"+PLAYER+"'), 'name')",env));
        assertEquals(Map.of(),eval("player_info('"+other+"')",env));
        assertEquals(false,eval("player_has_flag('"+other+"', 'vip')",env),"the default environment reads no other player");
        assertEquals("none",eval("player_flag('"+other+"', 'vip', 'none')",env));
    }
    @Test void npcListsInfoAndOtherNpcsFlagsReadTheRuntime(){
        UUID guard=UUID.fromString("00000000-0000-0000-0000-0000000000c3"),gone=UUID.fromString("00000000-0000-0000-0000-0000000000c4");
        Env env=new Env();
        env.npcFlags.put(guard+"/post",text("gate"));
        env.facts=new BehaviorFacts(){
            public Optional<Map<String,Object>> player(UUID id){return Optional.empty();}
            public Optional<Map<String,Object>> npc(UUID id){return id.equals(guard)?Optional.of(Map.of("name","Guard","citizens_id","12","spawned",true,"behaviors",List.of("denizen:assignment/guard"))):Optional.empty();}
            public Map<String,Object> server(){return Map.of();}
            @Override public List<UUID> npcs(){return List.of(guard,gone);}
        };
        assertEquals(List.of(guard.toString(),gone.toString()),eval("npcs()",env));
        assertEquals("12",eval("get(npc_info('"+guard+"'), 'citizens_id', '')",env));
        assertEquals(List.of("denizen:assignment/guard"),eval("get(npc_info('"+guard+"'), 'behaviors', [])",env));
        assertEquals(Map.of(),eval("npc_info('"+gone+"')",env));
        assertEquals(Map.of(),eval("npc_info('no npc')",env));
        assertEquals("gate",eval("npc_flag('"+guard+"', 'post')",env));
        assertEquals(true,eval("npc_has_flag('"+guard+"', 'post')",env));
        assertEquals("none",eval("npc_flag('"+gone+"', 'post', 'none')",env));
        assertEquals(false,eval("npc_has_flag('"+gone+"', 'post')",env));
        assertEquals(List.of(),eval("npcs()",new Env()),"the default facts list no NPCs");
    }
    @Test void distanceMeasuresBetweenLocationsInOneWorld(){
        assertEquals(5.0,eval("distance('0,64,0,world', '3.00,64.00,4.00,world')"));
        assertEquals(5.0,eval("distance('0,64,0', '3,64,4,world')"),"a location without a world is in any world");
        assertEquals(1.0E9,eval("distance('0,64,0,world', '0,64,0,world_nether')"),"another world is far away");
        assertTrue(failure("distance('here', '0,64,0')",new Env()).getMessage().contains("location"));
    }
    @Test void durationsReadDenizenTextAndFormatAsDenizenDoes(){
        assertEquals(600.0,eval("duration_seconds('10m')"));
        assertEquals(1.5,eval("duration_seconds('30t')"));
        assertEquals(90000.0,eval("duration_seconds('d@1d1h')"),"parts add up");
        assertEquals(45.0,eval("duration_seconds(45)"));
        assertEquals(45.0,eval("duration_seconds('45')"));
        assertTrue(failure("duration_seconds('soon')",new Env()).getMessage().contains("duration"));
        assertEquals("2h 30m",eval("duration_text(9000)"));
        assertEquals("1d 3h",eval("duration_text(97300)"),"no minutes once a day is shown");
        assertEquals("5m 7s",eval("duration_text(307)"));
        assertEquals("12m",eval("duration_text(725)"),"no seconds from ten minutes on");
        assertEquals("0s",eval("duration_text(0)"));
    }
    @Test void typeOfNamesTheKindOfAValue(){
        assertEquals("list",eval("type_of([1, 2])"));
        Env env=new Env();env.variables.put("held",new Value(Type.MAP,Map.of("item",text("minecraft:bread"))));
        assertEquals("map",eval("type_of(held)",env));
        assertEquals("text",eval("type_of('bread')"));
        assertEquals("number",eval("type_of(3)"));
        assertEquals("boolean",eval("type_of(true)"));
    }
    @Test void locationsOffsetFindNearbyPlayersAndTestAreas(){
        UUID near=UUID.fromString("00000000-0000-0000-0000-0000000000b1"),far=UUID.fromString("00000000-0000-0000-0000-0000000000b2"),away=UUID.fromString("00000000-0000-0000-0000-0000000000b3");
        Env env=new Env();
        Map<UUID,String> places=Map.of(PLAYER,"10.00,64.00,3.00,town",near,"11.00,64.00,0.00,town",far,"40.00,64.00,0.00,town",away,"10.00,64.00,0.00,mine");
        env.facts=new BehaviorFacts(){
            public Optional<Map<String,Object>> player(UUID id){return Optional.ofNullable(places.get(id)).map((String at)->Map.of("location",at));}
            public Optional<Map<String,Object>> npc(UUID id){return Optional.empty();}
            public Map<String,Object> server(){return Map.of("players",List.of(PLAYER.toString(),near.toString(),far.toString(),away.toString()));}
        };
        assertEquals("10.00,66.50,0.00,town",eval("offset('10,64,0,town', 0, 2.5, 0)",env));
        assertEquals(List.of(near.toString(),PLAYER.toString()),eval("players_near('10,64,0,town', 5)",env),"nearest first, the same world only");
        assertEquals(true,eval("has_player()",env));
        env.player=Optional.empty();
        assertEquals(false,eval("has_player()",env));
        env.variables.put("hall",new Value(Type.MAP,Map.of("world",text("town"),"shape",text("cuboid"),"bounds",new Value(Type.LIST,List.of(integer(0),integer(60),integer(0),integer(10),integer(70),integer(10))))));
        assertEquals(true,eval("within('10.9,65,0.5,town', hall)",env),"the corner blocks are inside");
        assertEquals(false,eval("within('11,65,0,town', hall)",env));
        assertEquals(false,eval("within('5,65,5,mine', hall)",env));
        env.variables.put("pond",new Value(Type.MAP,Map.of("world",text("town"),"shape",text("ellipsoid"),"bounds",new Value(Type.LIST,List.of(integer(0),integer(60),integer(0),integer(4),integer(2),integer(4))))));
        assertEquals(true,eval("within('3,60,0,town', pond)",env));
        assertEquals(false,eval("within('3,61.5,2,town', pond)",env));
    }
    @Test void playersAreFoundByNameAsDenizenMatchesThem(){
        UUID bob=UUID.fromString("00000000-0000-0000-0000-0000000000c1"),bobby=UUID.fromString("00000000-0000-0000-0000-0000000000c2"),bo=UUID.fromString("00000000-0000-0000-0000-0000000000c3");
        Map<UUID,String> names=Map.of(bob,"Bob",bobby,"Bobby",bo,"bo");
        Env env=new Env();
        env.facts=new BehaviorFacts(){
            public Optional<Map<String,Object>> player(UUID id){return Optional.ofNullable(names.get(id)).map((String name)->Map.of("name",name));}
            public Optional<Map<String,Object>> npc(UUID id){return Optional.empty();}
            public Map<String,Object> server(){return Map.of("players",List.of(bobby.toString(),bob.toString(),bo.toString()));}
        };
        assertEquals(bob.toString(),eval("player_named('BOB')",env),"an exact name wins");
        assertEquals(bobby.toString(),eval("player_named('bobb')",env));
        assertEquals(bo.toString(),eval("player_named('b')",env),"the shortest name that starts with it");
        assertEquals("",eval("player_named('zed')",env));
        assertTrue((Double)eval("now()",env)>1.7e12);
    }
    @Test void flagExpiriesReadTheirEpochMillisOrZero(){
        Env env=new Env();env.expiries.put("player/cooldown",90_000L);
        assertEquals(90_000.0,eval("flag_expiry('player', 'cooldown')",env));
        assertEquals(0.0,eval("flag_expiry('player', 'other')",env));
        UUID other=UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        env.expiries.put(other+"/cooldown",5_000L);
        assertEquals(5_000.0,eval("player_flag_expiry('"+other+"', 'cooldown')",env));
        assertEquals(0.0,eval("player_flag_expiry('nobody', 'cooldown')",env));
    }
}
