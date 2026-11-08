package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.virtual.Vec3;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Coordinate words read as vanilla {@code /tp} reads them, and the commands that take them. */
class CoordinatesTest {
    private static final Optional<Coordinates.Origin> SOUTH = Optional.of(new Coordinates.Origin(new Vec3(10.5, 64, -3.25), new Coordinates.Facing(0, 0)));

    @Test void numbersAreAbsolute() {
        assertEquals(new Vec3(12, 70.5, -30), Coordinates.point("12", "70.5", "-30", SOUTH));
        assertEquals(new Vec3(1, 2, 3), Coordinates.point("1", "2", "3", Optional.empty()), "the console may give numbers");
    }

    @Test void tildeCountsFromWhereThePlayerStands() {
        assertEquals(new Vec3(10.5, 64, -3.25), Coordinates.point("~", "~", "~", SOUTH));
        assertEquals(new Vec3(12.5, 63, -3.25), Coordinates.point("~2", "~-1", "~", SOUTH));
        assertEquals(new Vec3(10.5, 65.5, 4), Coordinates.point("", "~1.5", "4", SOUTH), "a left-out word is the player's own value");
    }

    @Test void caretIsLocalToWhereThePlayerFaces() {
        assertEquals(new Vec3(10.5, 64, -1.25), Coordinates.point("^", "^", "^2", SOUTH), "yaw 0 faces south, +z");
        Optional<Coordinates.Origin> east = Optional.of(new Coordinates.Origin(new Vec3(0, 64, 0), new Coordinates.Facing(-90, 0)));
        assertEquals(new Vec3(3, 64, 0), Coordinates.point("^", "^", "^3", east), "yaw -90 faces east, +x");
        assertEquals(new Vec3(0, 65, -1), Coordinates.point("^1", "^1", "^", east), "left of east is north, -z");
        Optional<Coordinates.Origin> up = Optional.of(new Coordinates.Origin(new Vec3(0, 64, 0), new Coordinates.Facing(0, -90)));
        assertEquals(new Vec3(0, 66, 0), Coordinates.point("^", "^", "^2", up), "pitch -90 looks straight up");
        IllegalArgumentException mixed = assertThrows(IllegalArgumentException.class, () -> Coordinates.point("^", "~", "^", SOUTH));
        assertTrue(mixed.getMessage().contains("all three or none"), mixed.getMessage());
    }

    @Test void theConsoleMustGiveNumbers() {
        for (String[] words : List.of(new String[]{"", "64", "0"}, new String[]{"~", "64", "0"}, new String[]{"^", "^", "^"})) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> Coordinates.point(words[0], words[1], words[2], Optional.empty()));
            assertEquals(Coordinates.CONSOLE, refused.getMessage());
        }
        IllegalArgumentException word = assertThrows(IllegalArgumentException.class, () -> Coordinates.point("~east", "1", "1", SOUTH));
        assertEquals("x must be a number, ~ or ~N, such as x=12.5 or x=~2", word.getMessage());
    }

    @Test void blockTriplesResolveOnlyWhenRelative() {
        assertEquals("12,64,-30", Coordinates.block("12,64,-30", Optional.empty()));
        assertEquals("10,63,-4", Coordinates.block("~,~-1,~", SOUTH));
        assertThrows(IllegalArgumentException.class, () -> Coordinates.block("~,~", SOUTH));
    }

    @Test void coordinateWordsAreRecognised() {
        for (String word : List.of("1", "-2.5", "~", "~3", "~-0.5", "^", "^2")) assertTrue(Coordinates.coordinate(word), word);
        for (String word : List.of("", "guard", "x=1", "~a", "1.", "@selected")) assertFalse(Coordinates.coordinate(word), word);
    }

    @Test void bareCoordinatesFillTheOptionalAxesAfterTheRequiredValues() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        DirectorRuntimeNode create = child(director.engine().getRoot(), "create");
        assertEquals(List.of("guard", "x=~", "y=~1", "z=~"), AnimusDirector.coordinates(create, List.of("guard", "~", "~1", "~")));
        assertEquals(List.of("guard", "type=minecraft:villager", "x=1", "y=2", "z=3"),
                AnimusDirector.coordinates(create, List.of("guard", "type=minecraft:villager", "1", "2", "3")));
        assertEquals(List.of("guard", "x=1", "2", "3"), AnimusDirector.coordinates(create, List.of("guard", "x=1", "2", "3")), "a keyed axis turns the shorthand off");
        assertEquals(List.of("guard", "1", "2"), AnimusDirector.coordinates(create, List.of("guard", "1", "2")), "two words are not a position");
        DirectorRuntimeNode go = child(child(director.engine().getRoot(), "navigation"), "go");
        assertEquals(List.of("guard", "x=10", "y=64", "z=10", "mode=fly"), AnimusDirector.coordinates(go, List.of("guard", "10", "64", "10", "mode=fly")));
        DirectorRuntimeNode list = child(director.engine().getRoot(), "list");
        assertEquals(List.of("1", "2", "3"), AnimusDirector.coordinates(list, List.of("1", "2", "3")), "a command without a position is left alone");
    }

    @Test void completionOffersTildesThenTheLookedAtBlock() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        AnimusDirectorTest.Actor player = new AnimusDirectorTest.Actor(true, AnimusDirector.ADMIN) {
            @Override public Optional<art.arcane.animus.core.virtual.WorldPosition> position() {
                return Optional.of(new art.arcane.animus.core.virtual.WorldPosition("world", new Vec3(0.5, 64, 0.5)));
            }
            @Override public Optional<Vec3> target() { return Optional.of(new Vec3(12, 63, -30)); }
        };
        assertEquals(List.of("~ ~ ~", "12 63 -30"), director.complete(player, new String[]{"create", "guard", ""}).subList(0, 2));
        assertEquals(List.of("~ ~", "63 -30"), director.complete(player, new String[]{"create", "guard", "~", ""}).subList(0, 2));
        List<String> console = director.complete(new AnimusDirectorTest.Actor(false, AnimusDirector.ADMIN), new String[]{"create", "guard", ""});
        assertFalse(console.contains("~ ~ ~"), "the console has no position to offer: " + console);
    }

    private static DirectorRuntimeNode child(DirectorRuntimeNode group, String name) {
        return group.getChildren().stream().filter((DirectorRuntimeNode node) -> node.getDescriptor().getName().equals(name)).findFirst().orElseThrow();
    }
}
