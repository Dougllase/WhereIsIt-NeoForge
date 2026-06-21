package red.jackf.whereisit.client.data;

import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;
import red.jackf.whereisit.api.SearchRequest;

import java.util.Objects;

/**
 * Immutable description of a single tracked item.
 *
 * - {@code item} is the canonical key used by the tracker manager.
 * - {@code request} is the matcher that produces results for this target.
 * - {@code color} is the assigned gradient color (rgba) used by world / slot highlighting.
 * - {@code addedAtTick} is the client tick the target was added; used for ordering and TTL.
 */
public record TrackingTarget(
        Item item,
        SearchRequest request,
        int color,
        long addedAtTick,
        @Nullable String displayName
) {
    public TrackingTarget {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(request, "request");
    }
}
