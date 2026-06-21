package red.jackf.whereisit.client.tracking;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * One row in the item browser: a representative stack (for rendering/tooltip), the total number of this item across
 * nearby containers, how many distinct containers hold it, source mod information, and collection progress.
 */
public record ItemEntry(
        ItemStack sample,
        int totalItems,
        int containerCount,
        String sourceModId,
        String sourceModName,
        int collectedCount,
        int collectedContainers
) {
    /** Create a minimal entry without collection data (for initial aggregation). */
    public ItemEntry(ItemStack sample, int totalItems, int containerCount, String sourceModId, String sourceModName) {
        this(sample, totalItems, containerCount, sourceModId, sourceModName, 0, 0);
    }

    /** Return the Item key for map lookups. */
    public Item item() {
        return sample.getItem();
    }
}
