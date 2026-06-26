package red.jackf.whereisit.client.tracking;

import net.minecraft.resources.ResourceKey;
import red.jackf.whereisit.client.data.ContainerRecord;
import red.jackf.whereisit.client.inventory.ContainerObserver;
import red.jackf.whereisit.client.inventory.InventoryLedger;
import red.jackf.whereisit.client.inventory.LedgerCleanup;

import java.util.Collection;

/**
 * Adapter that delegates to the new {@link InventoryLedger} / {@link ContainerObserver} /
 * {@link LedgerCleanup} architecture while preserving the old static API used by legacy code.
 */
public final class ContainerTracker {
    private ContainerTracker() {}

    public static Collection<ContainerRecord> getRecords(ResourceKey<net.minecraft.world.level.Level> dimension) {
        return InventoryLedger.get().recordsIn(dimension);
    }

    public static void onJoinWorld() {
        // No-op: new architecture handles this internally.
    }

    public static void onLeaveWorld() {
        InventoryLedger.get().clearAll();
        ContainerObserver.reset();
        LedgerCleanup.reset();
        TrackingState.stopAll();
    }

    public static void tickAutosave() {
        // No-op: new architecture does not yet implement persistence.
    }

    public static void tick() {
        ContainerObserver.tick();
    }

    public static void tickCleanupScan() {
        LedgerCleanup.tick();
    }

    public static void clearAll() {
        InventoryLedger.get().clearAll();
    }

    public static void saveIfDirty() {
        // No-op: new architecture does not yet implement persistence.
    }
}
