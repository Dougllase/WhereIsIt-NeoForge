package red.jackf.whereisit.api.search;


import com.google.common.collect.Lists;
import red.jackf.whereisit.api.events.SimpleEvent;
import net.minecraft.world.item.ItemStack;
import red.jackf.whereisit.api.EventPhases;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Recursively gets a list of all items that are directly contained within another item, such as Shulker Boxes, bundles,
 * or backpacks. Not meant to be used with indirect storage, such as 'ender pouches' or remote terminals.
 */
public interface NestedItemsGrabber {
    /**
     * Maximum recursion depth when descending into nested containers. Most legitimate Vanilla / mod containers nest
     * at most 1-2 levels deep (e.g. a shulker inside a bundle); 8 leaves comfortable headroom while preventing
     * pathological mod data (or maliciously constructed NBT) from causing a {@link StackOverflowError}.
     */
    int MAX_NESTING_DEPTH = 8;

    /**
     * Get a stream of all items directly contained within this item.
     *
     * @param source ItemStack to pull items from.
     * @return Stream of items that are contained within this stack.
     */
    static Stream<ItemStack> get(ItemStack source) {
        return EVENT.invoker().grab(source);
    }

    SimpleEvent<NestedItemsGrabber> EVENT = SimpleEvent.createWithPhases(NestedItemsGrabber.class, listeners -> stack -> {
        // Use an identity-based visited set so cycles in mod-supplied container data cannot cause infinite recursion.
        // ItemStack does not override hashCode/equals on identity, so IdentityHashMap-backed sets are correct here.
        Set<ItemStack> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<ItemStack> result = Lists.newArrayList();
        collect(listeners, stack, result, visited, 0);
        return result.stream();
    }, EventPhases.PRIORITY, EventPhases.DEFAULT, EventPhases.FALLBACK);

    /**
     * Internal recursive walker with depth + cycle guard. Package-private so the lambda in {@link #EVENT} can call it.
     */
    private static void collect(NestedItemsGrabber[] listeners,
                                ItemStack stack,
                                List<ItemStack> out,
                                Set<ItemStack> visited,
                                int depth) {
        if (depth >= MAX_NESTING_DEPTH) return;
        if (!visited.add(stack)) return;

        for (NestedItemsGrabber listener : listeners) {
            listener.grab(stack).forEach(nested -> {
                if (nested == null || nested.isEmpty()) return;
                out.add(nested);
                collect(listeners, nested, out, visited, depth + 1);
            });
        }
    }

    /**
     * Pulls a stream of item stacks from a source stack. Should return a {@link Stream#empty()} if none are contained.
     *
     * @param source ItemStack to pull items from.
     * @return Stream of items that are contained within this stack.
     */
    Stream<ItemStack> grab(ItemStack source);
}
