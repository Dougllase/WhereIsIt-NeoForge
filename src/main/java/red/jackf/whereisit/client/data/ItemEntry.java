package red.jackf.whereisit.client.data;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * Immutable summary of an item stack seen in inventory.
 * Stores a representative ItemStack plus an aggregated count across one or more containers.
 *
 * Equality is by item identity + components patch; count is informational.
 */
public record ItemEntry(ItemStack representative, int count) {
    public ItemEntry {
        Objects.requireNonNull(representative, "representative");
        if (representative.isEmpty()) {
            throw new IllegalArgumentException("ItemEntry cannot wrap an empty stack");
        }
        if (count < 0) {
            count = 0;
        }
    }

    public static ItemEntry of(ItemStack stack) {
        return new ItemEntry(stack.copy(), stack.getCount());
    }

    public Item item() {
        return representative.getItem();
    }

    /**
     * Returns a new ItemEntry with the count increased by the given amount.
     */
    public ItemEntry plus(int extra) {
        return new ItemEntry(representative, count + extra);
    }

    /**
     * Returns a fresh, mutable copy of the representative stack with the aggregated count.
     */
    public ItemStack toDisplayStack() {
        ItemStack copy = representative.copy();
        copy.setCount(Math.max(1, count));
        return copy;
    }
}
