package red.jackf.whereisit.client.tracking;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A snapshot of a single container's contents, recorded client-side when the player closes the container screen.
 *
 * @param dimension Dimension the container lives in.
 * @param pos       Block position of the container.
 * @param contents  Item stacks present in the container (excluding the player's own inventory slots).
 * @param gameTick  Game tick when the snapshot was taken (for staleness checks).
 */
public record ContainerRecord(ResourceKey<Level> dimension, BlockPos pos, List<ItemStack> contents, long gameTick) {
}
