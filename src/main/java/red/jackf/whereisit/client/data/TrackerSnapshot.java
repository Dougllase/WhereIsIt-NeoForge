package red.jackf.whereisit.client.data;

import net.minecraft.world.item.Item;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of {@link red.jackf.whereisit.client.tracking.TrackerManager} state.
 * Consumed by HUD and render layers so they only see a stable view.
 */
public record TrackerSnapshot(
        List<TrackingTarget> targets,
        Map<Item, List<ClientSearchResult>> resultsByItem,
        Map<Item, Integer> totalCountByItem
) {
    public static final TrackerSnapshot EMPTY =
            new TrackerSnapshot(List.of(), Map.of(), Map.of());

    public TrackerSnapshot {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(resultsByItem, "resultsByItem");
        Objects.requireNonNull(totalCountByItem, "totalCountByItem");
        targets = List.copyOf(targets);

        // deep copy the maps with unmodifiable value lists
        Map<Item, List<ClientSearchResult>> copy = new LinkedHashMap<>();
        for (Map.Entry<Item, List<ClientSearchResult>> e : resultsByItem.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        resultsByItem = Collections.unmodifiableMap(copy);
        totalCountByItem = Map.copyOf(totalCountByItem);
    }

    public boolean isActive() {
        return !targets.isEmpty();
    }

    public Set<Item> trackedItems() {
        Set<Item> set = new LinkedHashSet<>();
        for (TrackingTarget t : targets) set.add(t.item());
        return Collections.unmodifiableSet(set);
    }
}
