package red.jackf.whereisit.client.data;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;

/**
 * Immutable identifier for a position in a specific dimension.
 * Equality is by dimension + block position, suitable for use as a Map/Set key.
 */
public record WorldCoordinate(ResourceKey<Level> dimension, BlockPos pos) {
    public WorldCoordinate {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(pos, "pos");
        // Always store an immutable BlockPos so external mutation never leaks into our key.
        if (pos.getClass() != BlockPos.class) {
            pos = pos.immutable();
        }
    }

    public static WorldCoordinate of(ResourceKey<Level> dimension, BlockPos pos) {
        return new WorldCoordinate(dimension, pos.immutable());
    }

    public boolean inDimension(ResourceKey<Level> other) {
        return dimension.equals(other);
    }
}
