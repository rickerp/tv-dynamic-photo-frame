package home.tvphotos;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PlayingTest {
    private static final List<String> ROUND = Arrays.asList("a.jpg", "b.jpg", "c1", "d.jpg", "c2");

    private static Set<String> set(String... keys) { return new HashSet<>(Arrays.asList(keys)); }

    @Test
    public void nothingOnPlaysEverything() {
        assertEquals(ROUND, Playing.filter(ROUND, Collections.emptyList()));
    }

    @Test
    public void onePlaylistKeepsOnlyItsItemsInRoundOrder() {
        assertEquals(Arrays.asList("b.jpg", "c1"), Playing.filter(ROUND, Collections.singletonList(set("c1", "b.jpg"))));
    }

    @Test
    public void twoPlaylistsPlayTheirUnion() {
        assertEquals(Arrays.asList("a.jpg", "d.jpg", "c2"),
                Playing.filter(ROUND, Arrays.asList(set("a.jpg", "c2"), set("d.jpg", "a.jpg"))));
    }

    @Test
    public void playlistsWithNothingToShowFallBackToEverything() {
        assertEquals(ROUND, Playing.filter(ROUND, Arrays.asList(set("gone.jpg"), set())));
    }
}
