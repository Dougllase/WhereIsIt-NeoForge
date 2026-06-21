package red.jackf.whereisit.client.tracking;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Aggregates every distinct item found in the player's recorded containers within a radius, deduplicating by
 * {@link Item} and counting the total item count plus how many distinct containers hold it. Used by both the
 * item browser screen (list display) and tracking mode (which only needs the range filter).
 */
public final class ItemAggregator {
    private ItemAggregator() {}

    /**
     * One row in the browser: a representative stack (for rendering/tooltip), the total number of this item across
     * nearby containers, and how many distinct containers contain it.
     */
    public record AggregatedItem(ItemStack sample, int totalItems, int containerCount) {}

    /**
     * @param dimension Dimension to scan.
     * @param playerPos Player position (feet) to measure range from.
     * @param range     Max distance in blocks (block-centre to player).
     * @return Items found in recorded containers within range, sorted by total count descending.
     */
    public static List<AggregatedItem> aggregate(ResourceKey<Level> dimension, Vec3 playerPos, int range) {
        Map<Item, AggregatedItem> map = new LinkedHashMap<>();
        double rangeSq = (double) range * (double) range;
        for (ContainerRecord record : ContainerTracker.getRecords(dimension)) {
            if (Vec3.atCenterOf(record.pos()).distanceToSqr(playerPos) > rangeSq) continue;

            // Per-container seen set so each container contributes at most +1 to an item's containerCount.
            Set<Item> seenInThisContainer = new HashSet<>();
            for (ItemStack stack : record.contents()) {
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                boolean firstTimeInThisContainer = seenInThisContainer.add(item);
                AggregatedItem existing = map.get(item);
                if (existing == null) {
                    map.put(item, new AggregatedItem(stack.copy(), stack.getCount(), 1));
                } else {
                    int newContainerCount = existing.containerCount() + (firstTimeInThisContainer ? 1 : 0);
                    map.put(item, new AggregatedItem(existing.sample(), existing.totalItems() + stack.getCount(), newContainerCount));
                }
            }
        }
        List<AggregatedItem> list = new ArrayList<>(map.values());
        list.sort(Comparator.comparingInt(AggregatedItem::totalItems).reversed());
        return list;
    }

    /**
     * Aggregate with full mod info and collection data for the item browser.
     *
     * @param dimension Dimension to scan.
     * @param playerPos Player position (feet) to measure range from.
     * @param range     Max distance in blocks (block-centre to player).
     * @return Items found in recorded containers within range, with source mod and collection info.
     */
    public static List<ItemEntry> aggregateWithModInfo(ResourceKey<Level> dimension, Vec3 playerPos, int range) {
        Map<Item, ItemEntry> map = new LinkedHashMap<>();
        double rangeSq = (double) range * (double) range;
        for (ContainerRecord record : ContainerTracker.getRecords(dimension)) {
            if (Vec3.atCenterOf(record.pos()).distanceToSqr(playerPos) > rangeSq) continue;

            Set<Item> seenInThisContainer = new HashSet<>();
            for (ItemStack stack : record.contents()) {
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                boolean firstTimeInThisContainer = seenInThisContainer.add(item);
                ItemEntry existing = map.get(item);
                if (existing == null) {
                    String modId = getModId(item);
                    String modName = getModName(modId);
                    map.put(item, new ItemEntry(stack.copy(), stack.getCount(), 1, modId, modName));
                } else {
                    int newContainerCount = existing.containerCount() + (firstTimeInThisContainer ? 1 : 0);
                    map.put(item, new ItemEntry(existing.sample(), existing.totalItems() + stack.getCount(),
                            newContainerCount, existing.sourceModId(), existing.sourceModName()));
                }
            }
        }

        // Merge collection data from ContainerTracker
        Map<Item, ContainerTracker.CollectionData> collectionData = ContainerTracker.getCollectionData();
        List<ItemEntry> list = new ArrayList<>();
        for (Map.Entry<Item, ItemEntry> e : map.entrySet()) {
            ItemEntry entry = e.getValue();
            ContainerTracker.CollectionData cd = collectionData.get(e.getKey());
            if (cd != null) {
                list.add(new ItemEntry(entry.sample(), entry.totalItems(), entry.containerCount(),
                        entry.sourceModId(), entry.sourceModName(), cd.count(), cd.containerCount()));
            } else {
                list.add(entry);
            }
        }
        list.sort(Comparator.comparingInt(ItemEntry::totalItems).reversed());
        return list;
    }

    /** Extract the namespace (mod ID) from an Item's registry name. */
    public static String getModId(Item item) {
        ResourceLocation rl = item.builtInRegistryHolder().key().location();
        return rl != null ? rl.getNamespace() : "minecraft";
    }

    /** Get a human-readable mod name from its ID. Tries localized name first, falls back to display name. */
    public static String getModName(String modId) {
        if ("minecraft".equals(modId)) return "Minecraft";
        // Try localized mod name via I18n (e.g. "modmenu.nameTranslation.create" → "机械动力")
        String translationKey = "modmenu.nameTranslation." + modId;
        if (net.minecraft.client.resources.language.I18n.exists(translationKey)) {
            String localized = net.minecraft.client.resources.language.I18n.get(translationKey);
            if (localized != null && !localized.equals(translationKey)) return localized;
        }
        try {
            Optional<? extends net.neoforged.fml.ModContainer> container =
                    ModList.get().getModContainerById(modId);
            return container.map(c -> c.getModInfo().getDisplayName()).orElse(modId);
        } catch (Exception e) {
            return modId;
        }
    }
}
