package red.jackf.whereisit.client.contentscanner;

import net.minecraft.client.Minecraft;
import red.jackf.whereisit.WhereIsIt;

/**
 * Periodically scans the client-side world to populate the
 * {@link red.jackf.whereisit.client.inventory.InventoryLedger}.
 *
 * <p>In the rewritten client-only architecture the server-side scan packet has been removed.
 * The scanner is currently disabled; it will be replaced by a local chunk scan once the
 * client-side container access API is in place.</p>
 */
public final class BackgroundScanner {
    private BackgroundScanner() {}

    /** Ticks between automatic world scans (20 ticks = 1 second). */
    private static final int SCAN_INTERVAL_TICKS = 200; // 10 seconds

    private static boolean enabled = false; // disabled until local scan is implemented
    private static int tickCounter = 0;
    private static int scanCount = 0;

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

        // TODO: implement local client-side chunk scan
        scanCount++;
        WhereIsIt.LOGGER.debug("BackgroundScanner local scan #{} skipped (not implemented)", scanCount);
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
