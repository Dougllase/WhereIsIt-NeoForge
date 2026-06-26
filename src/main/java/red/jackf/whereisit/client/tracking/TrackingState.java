package red.jackf.whereisit.client.tracking;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.criteria.builtin.ItemCriterion;
import red.jackf.whereisit.client.data.TrackingTarget;

import java.util.*;

/**
 * Minimal tracking state manager. Tracks which items the user has chosen to continuously
 * highlight, their associated search requests, and display colors.
 */
public final class TrackingState {
    private TrackingState() {}

    private static final Map<Item, TrackingTarget> targets = new LinkedHashMap<>();
    private static int nextColorIndex = 0;

    private static final int[] COLORS = {
            0xFF_00FF00, 0xFF_FF0000, 0xFF_0000FF, 0xFF_FFFF00,
            0xFF_FF00FF, 0xFF_00FFFF, 0xFF_FF8800, 0xFF_8800FF
    };

    public static boolean isTracking() {
        return !targets.isEmpty();
    }

    public static boolean isTracking(Item item) {
        return targets.containsKey(item);
    }

    public static void startTracking(ItemStack stack) {
        Item item = stack.getItem();
        if (targets.containsKey(item)) return;

        SearchRequest request = new SearchRequest();
        request.accept(new ItemCriterion(item));

        int color = COLORS[nextColorIndex % COLORS.length];
        nextColorIndex++;

        long tick = 0;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null) tick = mc.level.getGameTime();

        targets.put(item, new TrackingTarget(item, request, color, tick, stack.getHoverName().getString()));
    }

    public static void stopTracking(Item item) {
        targets.remove(item);
    }

    public static void stopAll() {
        targets.clear();
        nextColorIndex = 0;
    }

    public static List<Item> getTrackedItems() {
        return List.copyOf(targets.keySet());
    }

    public static int getTrackedColor(Item item) {
        TrackingTarget t = targets.get(item);
        return t != null ? t.color() : 0xFF_FFFFFF;
    }

    public static SearchRequest getTrackedRequest(Item item) {
        TrackingTarget t = targets.get(item);
        return t != null ? t.request() : null;
    }

    public static void tickRefresh() {
        // No-op in this minimal implementation.
        // In the full version, this would re-query the ledger for tracked items.
    }
}
