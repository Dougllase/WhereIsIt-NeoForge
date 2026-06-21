package red.jackf.whereisit.client.inventory;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.phys.Vec3;
import red.jackf.whereisit.WhereIsIt;

/**
 * Periodically removes ledger entries whose backing blocks no longer exist.
 *
 * <p>Runs at most every {@link #SCAN_INTERVAL_TICKS} ticks, only within {@link #SCAN_RADIUS} blocks
 * of the player, and only in the current dimension. This keeps the cost bounded regardless of how
 * many containers the player has observed.</p>
 */
public final class LedgerCleanup {
    private LedgerCleanup() {}

    private static final int SCAN_INTERVAL_TICKS = 200; // ~10s
    private static final int SCAN_RADIUS = 32;

    private static int counter = 0;

    public static void tick() {
        if (++counter < SCAN_INTERVAL_TICKS) return;
        counter = 0;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        var dim = mc.level.dimension();
        Vec3 playerCenter = mc.player.position();
        double radiusSq = (double) SCAN_RADIUS * SCAN_RADIUS;

        int removed = InventoryLedger.get().removeMatching(dim, pos -> {
            BlockPos center = pos.offset(0, 0, 0);
            double dx = (center.getX() + 0.5) - playerCenter.x;
            double dy = (center.getY() + 0.5) - playerCenter.y;
            double dz = (center.getZ() + 0.5) - playerCenter.z;
            if (dx * dx + dy * dy + dz * dz > radiusSq) return false;
            return !(mc.level.getBlockEntity(pos) instanceof BaseContainerBlockEntity);
        });

        if (removed > 0) {
            WhereIsIt.LOGGER.debug("LedgerCleanup removed {} stale records in {}",
                    removed, dim.location());
        }
    }

    public static void reset() {
        counter = 0;
    }
}
