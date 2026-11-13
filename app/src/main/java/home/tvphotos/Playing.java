package home.tvphotos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Which slides a round plays, given the playlists switched on. Pure Java, unit tested. */
final class Playing {
    private Playing() {}

    /**
     * The keys (photo names and combination ids) that belong to any switched-on playlist, in round order.
     * With no playlist on, or nothing of theirs in the round, every key plays, so the screen is never empty.
     */
    static List<String> filter(List<String> keys, Collection<Set<String>> on) {
        if (on.isEmpty()) return keys;
        List<String> out = new ArrayList<>();
        for (String k : keys) {
            for (Set<String> items : on) {
                if (items.contains(k)) { out.add(k); break; }
            }
        }
        return out.isEmpty() ? keys : out;
    }
}
