package red.jackf.whereisit.client.tracking;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import red.jackf.whereisit.api.SearchRequest;

import java.util.*;

/**
 * Compatibility delegate. Forwards to {@link TrackerManager}.
 */
public final class TrackingState {
    private TrackingState() {}

    public static boolean isTracking() {
        return TrackerManager.isTracking();
    }

    public static boolean isTracking(Item item) {
        return TrackerManager.isTracking(item);
    }

    public static void startTracking(ItemStack stack) {
        TrackerManager.startTracking(stack);
    }

    public static void stopTracking(Item item) {
        TrackerManager.stopTracking(item);
    }

    public static void stopAll() {
        TrackerManager.stopAll();
    }

    public static List<Item> getTrackedItems() {
        return List.copyOf(TrackerManager.trackedItems());
    }

    public static int getTrackedColor(Item item) {
        return TrackerManager.trackedColor(item);
    }

    public static SearchRequest getTrackedRequest(Item item) {
        // TrackerManager stores TrackingTarget internally; SearchRequest is derivable.
        // For backward compat, reconstruct from item.
        return null; // no-op; caller should migrate to TrackerManager
    }

    public static void tickRefresh() {
        TrackerManager.tickRefresh();
    }
}
