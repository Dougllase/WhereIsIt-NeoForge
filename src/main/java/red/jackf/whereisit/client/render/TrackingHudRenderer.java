package red.jackf.whereisit.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.client.tracking.ItemAggregator;
import red.jackf.whereisit.client.tracking.ItemEntry;
import red.jackf.whereisit.client.tracking.TrackingState;
import red.jackf.whereisit.config.WhereIsItConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a tracking HUD overlay in the top-right corner (above the scoreboard sidebar),
 * showing all currently tracked items with their collection progress.
 */
@EventBusSubscriber(modid = WhereIsIt.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class TrackingHudRenderer {

    private static final int LINE_HEIGHT = 20;
    private static final int ICON_SIZE = 16;
    private static final int PADDING = 4;
    private static final int BG_PADDING = 2;

    // Cached aggregation data — refreshed every 20 ticks (1 second) instead of every frame.
    private static List<ItemEntry> cachedEntries = List.of();
    private static Map<Item, ItemEntry> cachedEntryMap = Map.of();
    private static long lastCacheTick = -1;

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!TrackingState.isTracking()) return;
        if (!WhereIsItConfig.INSTANCE.instance().getCommon().showTrackingHud) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        GuiGraphics graphics = event.getGuiGraphics();
        List<Item> trackedItems = TrackingState.getTrackedItems();
        if (trackedItems.isEmpty()) return;

        float scale = WhereIsItConfig.INSTANCE.instance().getCommon().trackingHudScale;
        int range = WhereIsItConfig.INSTANCE.instance().getCommon().trackingRangeBlocks;

    // Refresh cached data at most once every 20 ticks (1 second)
        long currentTick = mc.level.getGameTime();
        if (currentTick - lastCacheTick >= 20 || lastCacheTick < 0) {
            lastCacheTick = currentTick;
            cachedEntries = ItemAggregator.aggregateWithModInfo(
                    mc.level.dimension(), mc.player.position(), range);
            cachedEntryMap = new HashMap<>();
            for (ItemEntry entry : cachedEntries) {
                cachedEntryMap.put(entry.item(), entry);
            }
        }
        Map<Item, ItemEntry> entryMap = cachedEntryMap;

        // Calculate HUD dimensions
        int maxTextWidth = 0;
        for (Item item : trackedItems) {
            ItemEntry entry = entryMap.get(item);
            String name = getItemDisplayName(item, entry);
            String info = formatCollectionInfo(item, entry);
            String line = name + "  " + info;
            int width = mc.font.width(line) + ICON_SIZE + PADDING * 3;
            maxTextWidth = Math.max(maxTextWidth, width);
        }

        int hudWidth = maxTextWidth + BG_PADDING * 2;
        int hudHeight = trackedItems.size() * LINE_HEIGHT + BG_PADDING * 2;

        // Position: top-right corner, above scoreboard sidebar
        int hudX = mc.getWindow().getGuiScaledWidth() - hudWidth - PADDING;
        int hudY = PADDING;

        // Apply scale around the top-right pivot point
        if (scale != 1.0f) {
            graphics.pose().pushPose();
            // Scale around (hudX + hudWidth/2, hudY + hudHeight/2) to keep it anchored at top-right
            int pivotX = hudX + hudWidth;
            int pivotY = hudY;
            graphics.pose().translate(pivotX, pivotY, 0);
            graphics.pose().scale(scale, scale, 1.0f);
            graphics.pose().translate(-pivotX, -pivotY, 0);
        }

        // Background
        graphics.fill(hudX, hudY, hudX + hudWidth, hudY + hudHeight, 0x80_000000);

        // Render each tracked item
        int y = hudY + BG_PADDING;
        for (Item item : trackedItems) {
            ItemEntry entry = entryMap.get(item);
            int x = hudX + BG_PADDING;

            // Item icon — use entry's sample if available, else create from item
            ItemStack sample = entry != null ? entry.sample() : new ItemStack(item);
            graphics.renderItem(sample, x, y + (LINE_HEIGHT - ICON_SIZE) / 2);

            // Colour indicator dot
            int color = TrackingState.getTrackedColor(item);
            int dotX = x + ICON_SIZE + PADDING;
            int dotY = y + LINE_HEIGHT / 2 - 2;
            graphics.fill(dotX, dotY, dotX + 4, dotY + 4, color);

            // Text: item name + collection info
            String name = getItemDisplayName(item, entry);
            String info = formatCollectionInfo(item, entry);
            String text = name + "  " + info;
            graphics.drawString(mc.font, text, dotX + 6, y + (LINE_HEIGHT - 8) / 2, 0xFF_FFFFFF);

            y += LINE_HEIGHT;
        }

        if (scale != 1.0f) {
            graphics.pose().popPose();
        }
    }

    /** Get display name for an item, falling back to ItemStack name when entry is null. */
    private static String getItemDisplayName(Item item, @org.jetbrains.annotations.Nullable ItemEntry entry) {
        if (entry != null) return entry.sample().getHoverName().getString();
        // Item no longer in any container — use the ItemStack from TrackingState's request
        return new ItemStack(item).getHoverName().getString();
    }

    /**
     * Format collection info: collectedCount(collectedContainers)/originalTotal.
     * <p>Both currentRemaining and collected come from the same cached snapshot (entry),
     * so they are always consistent — no mismatch from one being stale and the other live.</p>
     * <p>originalTotal = currentRemaining + collectedCount, giving a meaningful progress ratio.</p>
     */
    private static String formatCollectionInfo(Item item, @org.jetbrains.annotations.Nullable ItemEntry entry) {
        int currentRemaining = 0;
        int collected = 0;
        int collectedContainers = 0;

        if (entry != null) {
            currentRemaining = entry.totalItems();
            collected = entry.collectedCount();
            collectedContainers = entry.collectedContainers();
        }

        int originalTotal = currentRemaining + collected;
        // Avoid division by zero display; show 0/0 as "—"
        if (originalTotal == 0) return "—";
        return String.format("%d(%d)/%d", collected, collectedContainers, originalTotal);
    }
}
