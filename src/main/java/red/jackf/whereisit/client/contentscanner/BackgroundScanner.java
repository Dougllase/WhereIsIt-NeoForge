package red.jackf.whereisit.client.contentscanner;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.network.C2SSearchRequest;

/**
 * Periodically triggers server-side world scans to populate the client-side
 * {@link red.jackf.whereisit.client.inventory.InventoryLedger} with all containers
 * in loaded chunks.
 *
 * <p>Uses the same {@link C2SSearchRequest} packet with a wildcard (empty-criteria)
 * request, which matches every item. Results are delivered through the normal
 * {@code S2CSearchResults → NetworkSearchInvoker} pipeline and also merged into
 * the ledger.</p>
 */
public final class BackgroundScanner {
    private BackgroundScanner() {}

    /** Ticks between automatic world scans (20 ticks = 1 second). */
    private static final int SCAN_INTERVAL_TICKS = 200; // 10 seconds

    private static boolean enabled = true;
    private static int tickCounter = 0;
    private static int scanCount = 0;

    /** Empty-criteria wildcard — matches everything. */
    private static final SearchRequest WILDCARD = new SearchRequest();

    /**
     * Called once per client tick from the top-level dispatcher.
     * Triggers a scan every {@link #SCAN_INTERVAL_TICKS} when enabled.
     */
    public static void tick() {
        if (!enabled) return;

        tickCounter++;
        if (tickCounter < SCAN_INTERVAL_TICKS) return;
        tickCounter = 0;

        var level = Minecraft.getInstance().level;
        if (level == null || Minecraft.getInstance().player == null) return;

        PacketDistributor.sendToServer(new C2SSearchRequest(WILDCARD));
        scanCount++;
        WhereIsIt.LOGGER.debug("BackgroundScanner scan #{} sent", scanCount);
    }

    /** Enable or disable background scanning at runtime. */
    public static void setEnabled(boolean enabled) {
        if (BackgroundScanner.enabled != enabled) {
            BackgroundScanner.enabled = enabled;
            WhereIsIt.LOGGER.debug("BackgroundScanner enabled={}", enabled);
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Reset state on world leave. */
    public static void reset() {
        tickCounter = 0;
        scanCount = 0;
    }
}
