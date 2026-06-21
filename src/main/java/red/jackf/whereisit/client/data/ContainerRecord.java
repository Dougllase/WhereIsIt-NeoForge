package red.jackf.whereisit.client.data;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Immutable record of a known container observed by the client.
 * Created and replaced by {@link red.jackf.whereisit.client.inventory.InventoryLedger}.
 *
 * - {@code key} uniquely identifies the container by dimension + primary block position.
 * - {@code connected} are additional block positions belonging to the same container
 *   (e.g. the second half of a double chest).
 * - {@code label} is an optional, user-facing name (e.g. custom container name).
 * - {@code lastUpdatedTick} is the client tick when this record was last refreshed.
 */
public record ContainerRecord(
        WorldCoordinate key,
        List<WorldCoordinate> connected,
        InventorySnapshot snapshot,
        @Nullable Component label,
        long lastUpdatedTick
) {
    public ContainerRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(snapshot, "snapshot");
        connected = connected == null ? List.of() : List.copyOf(connected);
    }

    public ContainerRecord withSnapshot(InventorySnapshot newSnapshot, long tick) {
        return new ContainerRecord(key, connected, newSnapshot, label, tick);
    }

    public ContainerRecord withLabel(@Nullable Component newLabel) {
        return new ContainerRecord(key, connected, snapshot, newLabel, lastUpdatedTick);
    }
}
