package red.jackf.whereisit.client.tracking;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import red.jackf.whereisit.client.data.ContainerRecord;
import red.jackf.whereisit.client.inventory.ContainerObserver;
import red.jackf.whereisit.client.inventory.InventoryLedger;
import red.jackf.whereisit.client.inventory.LedgerCleanup;
import red.jackf.whereisit.client.persistence.LedgerStorage;

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

    static {
        InventoryLedger.get().setOnChange(LedgerStorage::markDirty);
    }

    public static void onJoinWorld() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            LedgerStorage.load(mc.level.registryAccess());
        }
    }

    public static void onLeaveWorld() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            LedgerStorage.save(mc.level.registryAccess());
        }
        InventoryLedger.get().clearAll();
        ContainerObserver.reset();
        LedgerCleanup.reset();
        TrackingState.stopAll();
    }

    public static void tickAutosave() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && LedgerStorage.isDirty()) {
            LedgerStorage.save(mc.level.registryAccess());
        }
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            LedgerStorage.save(mc.level.registryAccess());
        }
    }
}
