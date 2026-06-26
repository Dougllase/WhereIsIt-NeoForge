package red.jackf.whereisit.client.tracking;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.criteria.builtin.ItemCriterion;
import red.jackf.whereisit.client.data.ClientSearchResult;
import red.jackf.whereisit.client.data.TrackingTarget;
import red.jackf.whereisit.client.search.SearchPipeline;

import java.util.*;

/**
 * Manages the lifecycle of tracked items: start, stop, periodic snapshot, and color assignment.
 *
 * <p>On each client tick, {@link #tickRefresh()} re-runs {@link SearchPipeline} for every tracked
 * item and caches the result as a {@link TrackerSnapshot}. Consumers (e.g. the HUD, world
 * renderer) read the snapshot via {@link #getSnapshot()}.</p>
 */
public final class TrackerManager {
    private TrackerManager() {}

    /** 8-cycle colour palette (same hues as upstream Where Is It). */
    private static final int[] PALETTE = {
            0xFF_00FF00, // green
            0xFF_FF0000, // red
            0xFF_0000FF, // blue
            0xFF_FFFF00, // yellow
            0xFF_FF00FF, // magenta
            0xFF_00FFFF, // cyan
            0xFF_FF8800, // orange
            0xFF_8800FF  // purple
    };

    private static final Map<Item, TrackingTarget> targets = new LinkedHashMap<>();
    private static int nextColor = 0;

    @Nullable
    private static volatile TrackerSnapshot lastSnapshot;

    // ---- lifecycle ----------------------------------------------------------------

    public static boolean isTracking() {
        return !targets.isEmpty();
    }

    public static boolean isTracking(Item item) {
        return targets.containsKey(item);
    }

    /** Start tracking an item. No-op if already tracked. */
    public static void startTracking(ItemStack stack) {
        Item item = stack.getItem();
        if (targets.containsKey(item)) return;

        SearchRequest request = new SearchRequest();
        request.accept(new ItemCriterion(item));

        int color = PALETTE[nextColor % PALETTE.length];
        nextColor++;

        long tick = 0L;
        var client = Minecraft.getInstance();
        if (client.level != null) tick = client.level.getGameTime();

        targets.put(item, new TrackingTarget(item, request, color, tick, stack.getHoverName().getString()));
    }

    /** Stop tracking a single item. */
    public static void stopTracking(Item item) {
        targets.remove(item);
    }

    /** Stop tracking all items and clear the cached snapshot. */
    public static void stopAll() {
        targets.clear();
        nextColor = 0;
        lastSnapshot = TrackerSnapshot.EMPTY;
    }

    /** @return unmodifiable view of the currently tracked items (ordered by registration). */
    public static Set<Item> trackedItems() {
        return Collections.unmodifiableSet(targets.keySet());
    }

    public static int trackedColor(Item item) {
        TrackingTarget t = targets.get(item);
        return t != null ? t.color() : 0xFF_FFFFFF;
    }

    // ---- snapshot ----------------------------------------------------------------

    /** @return the most recent snapshot, or {@link TrackerSnapshot#EMPTY}. */
    public static TrackerSnapshot getSnapshot() {
        return lastSnapshot != null ? lastSnapshot : TrackerSnapshot.EMPTY;
    }

    /**
     * Run the snapshot immediately (synchronous). Prefer {@link #tickRefresh()} for in-tick usage.
     */
    public static TrackerSnapshot capture() {
        var client = Minecraft.getInstance();
        if (client.level == null) {
            lastSnapshot = TrackerSnapshot.EMPTY;
            return lastSnapshot;
        }

        ResourceKey<Level> dim = client.level.dimension();
        Map<Item, List<ClientSearchResult>> hits = new LinkedHashMap<>();

        for (var entry : targets.entrySet()) {
            List<ClientSearchResult> results = SearchPipeline.execute(entry.getValue().request(), dim);
            hits.put(entry.getKey(), List.copyOf(results));
        }

        lastSnapshot = new TrackerSnapshot(hits, client.level.getGameTime());
        return lastSnapshot;
    }

    /** Called from the client tick event. Recalculates the snapshot. */
    public static void tickRefresh() {
        capture();
    }
}
