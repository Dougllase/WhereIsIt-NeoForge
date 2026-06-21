package red.jackf.whereisit.client.inventory;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.client.data.ContainerRecord;
import red.jackf.whereisit.client.data.InventorySnapshot;
import red.jackf.whereisit.client.data.WorldCoordinate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Authoritative store of every container the local player has observed.
 *
 * <p>The ledger is a pure data structure: it does <em>not</em> subscribe to events, scan blocks, or
 * persist itself. Those responsibilities live in {@link ContainerObserver} and (optionally)
 * future persistence layers. All public methods are thread-safe to the extent that
 * {@link ConcurrentHashMap} guarantees; mutation should still happen on the client thread.</p>
 *
 * <p>State is fully reset on dimension change / disconnect via {@link #clearAll()} so stale
 * positions never leak between worlds.</p>
 */
public final class InventoryLedger {
    private static final InventoryLedger INSTANCE = new InventoryLedger();

    public static InventoryLedger get() {
        return INSTANCE;
    }

    private final Map<ResourceKey<Level>, Map<BlockPos, ContainerRecord>> byDimension = new ConcurrentHashMap<>();

    private InventoryLedger() {}

    /**
     * Record (or replace) a snapshot for the container at the given coordinate.
     *
     * @param key       primary position + dimension (typically the root of a double chest)
     * @param connected additional positions belonging to the same container (may be empty)
     * @param snapshot  immutable item snapshot
     * @param label     optional display name (may be null)
     * @param tick      client tick at recording time
     * @return the new (immutable) record
     */
    public ContainerRecord record(WorldCoordinate key,
                                  List<WorldCoordinate> connected,
                                  InventorySnapshot snapshot,
                                  @Nullable Component label,
                                  long tick) {
        ContainerRecord record = new ContainerRecord(key, connected, snapshot, label, tick);
        byDimension
                .computeIfAbsent(key.dimension(), d -> new ConcurrentHashMap<>())
                .put(key.pos(), record);
        WhereIsIt.LOGGER.debug("InventoryLedger.record dim={} pos={} items={}",
                key.dimension().location(), key.pos(), snapshot.size());
        return record;
    }

    /**
     * Remove the record at the given coordinate. Returns the removed record, or null if absent.
     */
    public @Nullable ContainerRecord remove(WorldCoordinate key) {
        Map<BlockPos, ContainerRecord> dim = byDimension.get(key.dimension());
        if (dim == null) return null;
        ContainerRecord removed = dim.remove(key.pos());
        if (dim.isEmpty()) byDimension.remove(key.dimension());
        return removed;
    }

    /**
     * Remove every record whose primary position matches the predicate, within a single dimension.
     */
    public int removeMatching(ResourceKey<Level> dimension, Predicate<BlockPos> predicate) {
        Map<BlockPos, ContainerRecord> dim = byDimension.get(dimension);
        if (dim == null || dim.isEmpty()) return 0;
        List<BlockPos> doomed = new ArrayList<>();
        for (BlockPos pos : dim.keySet()) {
            if (predicate.test(pos)) doomed.add(pos);
        }
        for (BlockPos pos : doomed) dim.remove(pos);
        if (dim.isEmpty()) byDimension.remove(dimension);
        return doomed.size();
    }

    public void clearAll() {
        byDimension.clear();
        WhereIsIt.LOGGER.debug("InventoryLedger.clearAll");
    }

    public void clearDimension(ResourceKey<Level> dimension) {
        byDimension.remove(dimension);
    }

    /**
     * Immutable view of all records in the given dimension. The returned collection is a snapshot
     * (defensive copy); mutations to the ledger after the call do not affect the result.
     */
    public Collection<ContainerRecord> recordsIn(ResourceKey<Level> dimension) {
        Map<BlockPos, ContainerRecord> dim = byDimension.get(dimension);
        if (dim == null || dim.isEmpty()) return Collections.emptyList();
        return List.copyOf(dim.values());
    }

    public @Nullable ContainerRecord get(WorldCoordinate key) {
        Map<BlockPos, ContainerRecord> dim = byDimension.get(key.dimension());
        if (dim == null) return null;
        return dim.get(key.pos());
    }

    public int sizeIn(ResourceKey<Level> dimension) {
        Map<BlockPos, ContainerRecord> dim = byDimension.get(dimension);
        return dim == null ? 0 : dim.size();
    }

    public int totalSize() {
        int total = 0;
        for (Map<BlockPos, ContainerRecord> dim : byDimension.values()) total += dim.size();
        return total;
    }

    /**
     * Snapshot of the entire ledger keyed by dimension. Intended for debug / inspection only;
     * the returned map and inner maps are unmodifiable.
     */
    public Map<ResourceKey<Level>, Map<BlockPos, ContainerRecord>> debugSnapshot() {
        Map<ResourceKey<Level>, Map<BlockPos, ContainerRecord>> copy = new HashMap<>();
        for (var e : byDimension.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableMap(new HashMap<>(e.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }
}
