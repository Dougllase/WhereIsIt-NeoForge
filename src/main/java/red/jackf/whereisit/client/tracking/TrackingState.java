package red.jackf.whereisit.client.tracking;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.SearchResult;
import red.jackf.whereisit.client.WhereIsItClient;
import red.jackf.whereisit.client.api.events.SearchRequestPopulator;
import red.jackf.whereisit.client.render.Rendering;
import red.jackf.whereisit.config.WhereIsItConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Manages multi-item tracking: up to {@code maxTrackedItems} items can be tracked simultaneously, each with its own
 * search request and assigned colour. Periodically re-scans recorded containers within the tracking range, refreshing
 * {@link Rendering}'s results so highlights follow the player.
 */
public final class TrackingState {
    private TrackingState() {}

    private static final Random COLOR_RNG = new Random();

    /** Tracked items: Item -> SearchRequest */
    private static final Map<Item, SearchRequest> trackedItems = new LinkedHashMap<>();

    /** Assigned colours for each tracked item: Item -> ARGB colour */
    private static final Map<Item, Integer> trackedColors = new LinkedHashMap<>();

    private static long lastRefreshTick = 0L;

    public static boolean isTracking() {
        return !trackedItems.isEmpty();
    }

    public static boolean isTracking(Item item) {
        return trackedItems.containsKey(item);
    }

    /** Get all tracked items in insertion order. */
    public static List<Item> getTrackedItems() {
        return List.copyOf(trackedItems.keySet());
    }

    /** Get the search request for a tracked item. */
    public static SearchRequest getTrackedRequest(Item item) {
        return trackedItems.get(item);
    }

    /** Get the assigned colour for a tracked item (ARGB). */
    public static int getTrackedColor(Item item) {
        return trackedColors.getOrDefault(item, 0xFFFFFFFF);
    }

    /** Get the maximum number of items that can be tracked simultaneously. */
    public static int getMaxTrackedItems() {
        return WhereIsItConfig.INSTANCE.instance().getCommon().maxTrackedItems;
    }

    /** Begin tracking an item; returns true if added, false if already tracked or at limit. */
    public static boolean startTracking(ItemStack sample) {
        Item item = sample.getItem();
        if (trackedItems.containsKey(item)) return false;
        if (trackedItems.size() >= getMaxTrackedItems()) return false;

        SearchRequest request = new SearchRequest();
        SearchRequestPopulator.addItemStack(request, sample, SearchRequestPopulator.Context.INVENTORY);
        if (!request.hasCriteria()) return false;

        trackedItems.put(item, request);
        trackedColors.put(item, generateRandomColor());
        ContainerTracker.clearCollectionData(item);
        Rendering.setTrackingActive(true);
        refreshNow();
        return true;
    }

    /** Stop tracking a specific item. */
    public static void stopTracking(Item item) {
        trackedItems.remove(item);
        trackedColors.remove(item);
        ContainerTracker.clearCollectionData(item);
        if (trackedItems.isEmpty()) {
            Rendering.setTrackingActive(false);
            Rendering.clearResults();
        } else {
            refreshNow();
        }
    }

    /** Stop tracking all items. */
    public static void stopAll() {
        trackedItems.clear();
        trackedColors.clear();
        ContainerTracker.clearAllCollectionData();
        Rendering.setTrackingActive(false);
        Rendering.clearResults();
    }

    /**
     * Legacy method: start a one-shot search for a single request (used by Y-key search).
     * Clears all active tracking, then performs a regular search via {@link WhereIsItClient#doSearch(SearchRequest)}.
     */
    public static void start(SearchRequest request) {
        stopAll();
        WhereIsItClient.doSearch(request);
    }

    /**
     * Called every client tick. Re-scans within range at most once every {@code trackingRefreshTicks} so the
     * highlighted containers stay fresh as the player moves.
     */
    public static void tickRefresh() {
        if (trackedItems.isEmpty()) return;
        var level = Minecraft.getInstance().level;
        if (level == null) { stopAll(); return; }
        long now = level.getGameTime();
        int interval = WhereIsItConfig.INSTANCE.instance().getCommon().trackingRefreshTicks;
        if (now - lastRefreshTick < interval) return;
        lastRefreshTick = now;
        refreshNow();
    }

    /** Force an immediate re-scan regardless of the refresh throttle. */
    public static void refreshNow() {
        if (trackedItems.isEmpty()) return;
        var mc = Minecraft.getInstance();
        var level = mc.level;
        var player = mc.player;
        if (level == null || player == null) { stopAll(); return; }

        // Reset throttle so tickRefresh doesn't skip the next scheduled scan
        lastRefreshTick = level.getGameTime();

        ResourceKey<Level> dim = level.dimension();
        Vec3 playerPos = player.position();
        int range = WhereIsItConfig.INSTANCE.instance().getCommon().trackingRangeBlocks;
        double rangeSq = (double) range * (double) range;

        // Collect results per tracked item
        Map<Item, Collection<SearchResult>> resultsByItem = new LinkedHashMap<>();
        for (Item trackedItem : trackedItems.keySet()) {
            resultsByItem.put(trackedItem, new ArrayList<>());
        }

        for (ContainerRecord record : ContainerTracker.getRecords(dim)) {
            if (Vec3.atCenterOf(record.pos()).distanceToSqr(playerPos) > rangeSq) continue;
            for (Item trackedItem : trackedItems.keySet()) {
                SearchRequest request = trackedItems.get(trackedItem);
                for (ItemStack stack : record.contents()) {
                    if (SearchRequest.check(stack, request)) {
                        resultsByItem.get(trackedItem).add(
                                SearchResult.builder(record.pos()).item(stack).build());
                        break; // one match per container per tracked item
                    }
                }
            }
        }

        // Flatten into a single collection with item association for Rendering
        List<Rendering.TrackedResult> allResults = new ArrayList<>();
        for (Map.Entry<Item, Collection<SearchResult>> entry : resultsByItem.entrySet()) {
            for (SearchResult sr : entry.getValue()) {
                allResults.add(new Rendering.TrackedResult(sr, entry.getKey()));
            }
        }

        if (trackedItems.isEmpty()) {
            Rendering.setTrackingActive(false);
            Rendering.clearResults();
        } else {
            Rendering.setTrackedResults(allResults);
        }
    }

    /** Generate a random saturated colour (ARGB). */
    private static int generateRandomColor() {
        float hue = COLOR_RNG.nextFloat();
        float saturation = 0.8f;
        float brightness = 0.85f;
        int rgb = java.awt.Color.HSBtoRGB(hue, saturation, brightness);
        return 0xFF000000 | rgb;
    }
}
