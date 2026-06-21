package red.jackf.whereisit.client.data;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of the contents of a single container at a single point in time.
 * Stacks are stored as defensive copies so callers cannot mutate stored state.
 */
public final class InventorySnapshot {
    public static final InventorySnapshot EMPTY = new InventorySnapshot(Collections.emptyList(), 0L);

    private final List<ItemStack> stacks;
    private final long contentHash;

    private InventorySnapshot(List<ItemStack> stacks, long contentHash) {
        this.stacks = stacks;
        this.contentHash = contentHash;
    }

    public static InventorySnapshot of(List<ItemStack> source) {
        if (source == null || source.isEmpty()) return EMPTY;
        List<ItemStack> copies = new ArrayList<>(source.size());
        long hash = 1L;
        for (ItemStack stack : source) {
            if (stack == null || stack.isEmpty()) continue;
            ItemStack copy = stack.copy();
            copies.add(copy);
            hash = hash * 31L + stackHash(copy);
        }
        if (copies.isEmpty()) return EMPTY;
        return new InventorySnapshot(Collections.unmodifiableList(copies), hash);
    }

    private static long stackHash(ItemStack stack) {
        int itemHash = stack.getItem().hashCode();
        int componentsHash = Objects.hashCode(stack.getComponentsPatch());
        return ((long) itemHash << 32)
                ^ (componentsHash & 0xFFFFFFFFL)
                ^ ((long) stack.getCount() * 0x9E3779B97F4A7C15L);
    }

    public List<ItemStack> stacks() {
        return stacks;
    }

    public boolean isEmpty() {
        return stacks.isEmpty();
    }

    public int size() {
        return stacks.size();
    }

    public long contentHash() {
        return contentHash;
    }
}
