package red.jackf.whereisit.client.tracking;

import net.minecraft.world.item.Item;
import red.jackf.whereisit.client.data.ClientSearchResult;

import java.util.*;

/**
 * Immutable snapshot of all tracked items and their matched positions at a single game tick.
 */
public record TrackerSnapshot(
        Map<Item, List<ClientSearchResult>> hits,
        long tick
) {
    public static final TrackerSnapshot EMPTY = new TrackerSnapshot(Map.of(), 0L);

    public TrackerSnapshot {
        hits = Map.copyOf(hits);
    }

    public boolean isEmpty() {
        return hits.isEmpty();
    }

    public Set<Item> trackedItems() {
        return hits.keySet();
    }

    public List<ClientSearchResult> hitsFor(Item item) {
        return hits.getOrDefault(item, List.of());
    }
}
