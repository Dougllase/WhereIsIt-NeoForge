package red.jackf.whereisit.client.tracking;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import red.jackf.whereisit.client.data.ContainerRecord;
import red.jackf.whereisit.client.data.InventorySnapshot;
import red.jackf.whereisit.client.data.ItemEntry;
import red.jackf.whereisit.client.inventory.InventoryLedger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates item entries from the {@link InventoryLedger} for display in the item browser and HUD.
 */
public final class ItemAggregator {
    private ItemAggregator() {}

    /**
     * Aggregate all items in the ledger for the given dimension, filtering by distance from the player.
     */
    public static List<ItemEntry> aggregateWithModInfo(ResourceKey<Level> dimension, Vec3 playerPos, int range) {
        List<ContainerRecord> records = new ArrayList<>(InventoryLedger.get().recordsIn(dimension));
        if (records.isEmpty()) return List.of();

        double rangeSq = (double) range * range;
        // item -> [totalCount, containerCount]
        Map<Item, int[]> counts = new HashMap<>();
        Map<Item, ItemStack> samples = new HashMap<>();
        Map<Item, String> sourceMods = new HashMap<>();

        for (ContainerRecord record : records) {
            double dx = (record.key().pos().getX() + 0.5) - playerPos.x;
            double dy = (record.key().pos().getY() + 0.5) - playerPos.y;
            double dz = (record.key().pos().getZ() + 0.5) - playerPos.z;
            if (dx * dx + dy * dy + dz * dz > rangeSq) continue;

            InventorySnapshot snapshot = record.snapshot();
            if (snapshot == null || snapshot.isEmpty()) continue;

            // Track which items are in this container (for container count)
            java.util.Set<Item> itemsInThisContainer = new java.util.HashSet<>();

            for (ItemStack stack : snapshot.stacks()) {
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                counts.compute(item, (k, v) -> {
                    if (v == null) v = new int[]{0, 0};
                    v[0] += stack.getCount();
                    return v;
                });
                samples.putIfAbsent(item, stack.copy());
                itemsInThisContainer.add(item);
                // Use Minecraft as source for vanilla items
                sourceMods.putIfAbsent(item, "Minecraft");
            }

            // Increment container count for each item found in this container
            for (Item item : itemsInThisContainer) {
                counts.compute(item, (k, v) -> {
                    v[1]++;
                    return v;
                });
            }
        }

        List<ItemEntry> entries = new ArrayList<>();
        for (Map.Entry<Item, int[]> e : counts.entrySet()) {
            Item item = e.getKey();
            int totalCount = e.getValue()[0];
            int containerCnt = e.getValue()[1];
            ItemStack sample = samples.get(item).copy();
            String source = sourceMods.getOrDefault(item, "Unknown");
            entries.add(new ItemEntry(sample, totalCount, containerCnt, source));
        }

        return entries;
    }
}
