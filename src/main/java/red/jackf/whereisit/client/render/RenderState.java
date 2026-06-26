package red.jackf.whereisit.client.render;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import red.jackf.whereisit.client.data.ClientSearchResult;
import red.jackf.whereisit.client.tracking.TrackerManager;
import red.jackf.whereisit.client.tracking.TrackerSnapshot;

import java.util.*;

/**
 * Read-only render data distilled from a {@link TrackerSnapshot}.
 *
 * <p>Consumers (M6 world / slot highlight renderers) read from this record.</p>
 */
public record RenderState(
        List<PerItem> groups,
        long tick
) {
    public static final RenderState EMPTY = new RenderState(List.of(), 0L);

    /** All highlight data for a single tracked item. */
    public record PerItem(
            Item item,
            int color,
            List<Highlight> highlights
    ) {}

    /** A single block position (and its connected slots) to highlight, with optional label. */
    public record Highlight(
            BlockPos pos,
            @Nullable Component label,
            @Nullable Vec3 labelOffset,
            Set<BlockPos> connected
    ) {
        public Highlight {
            connected = Set.copyOf(connected);
        }
    }

    // ---- factory ----------------------------------------------------------------

    public static RenderState fromSnapshot(TrackerSnapshot snapshot) {
        if (snapshot.isEmpty()) return EMPTY;

        List<PerItem> groups = new ArrayList<>();
        for (Map.Entry<Item, List<ClientSearchResult>> entry : snapshot.hits().entrySet()) {
            Item item = entry.getKey();
            int color = TrackerManager.trackedColor(item);
            List<Highlight> highlights = new ArrayList<>();

            for (ClientSearchResult r : entry.getValue()) {
                highlights.add(new Highlight(
                        r.primary(),
                        r.label(),
                        r.labelOffset(),
                        r.connected()
                ));
            }
            if (!highlights.isEmpty()) {
                groups.add(new PerItem(item, color, List.copyOf(highlights)));
            }
        }
        return new RenderState(List.copyOf(groups), snapshot.tick());
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }
}
