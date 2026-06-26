package red.jackf.whereisit.client.data;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * Immutable summary of an item stack seen in inventory.
 * Stores a representative ItemStack plus aggregated count, container count, source mod,
 * and collection tracking data.
 */
public record ItemEntry(
        ItemStack sample,
        int totalItems,
        int containerCount,
        String sourceModName,
        int collectedCount,
        int collectedContainers
) {
    public ItemEntry {
        Objects.requireNonNull(sample, "sample");
        if (sample.isEmpty()) {
            throw new IllegalArgumentException("ItemEntry cannot wrap an empty stack");
        }
        if (totalItems < 0) totalItems = 0;
        if (containerCount < 0) containerCount = 0;
        if (collectedCount < 0) collectedCount = 0;
        if (collectedContainers < 0) collectedContainers = 0;
        if (sourceModName == null || sourceModName.isEmpty()) {
            sourceModName = "Unknown";
        }
    }

    /** Convenience constructor with zero collection data. */
    public ItemEntry(ItemStack sample, int totalItems, int containerCount, String sourceModName) {
        this(sample, totalItems, containerCount, sourceModName, 0, 0);
    }

    public Item item() {
        return sample.getItem();
    }
}
