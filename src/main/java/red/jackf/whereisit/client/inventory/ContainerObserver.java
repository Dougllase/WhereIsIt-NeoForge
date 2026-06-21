package red.jackf.whereisit.client.inventory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BeaconScreen;
import net.minecraft.client.gui.screens.inventory.CartographyTableScreen;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.client.gui.screens.inventory.LoomScreen;
import net.minecraft.client.gui.screens.inventory.StonecutterScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.api.search.ConnectedBlocksGrabber;
import red.jackf.whereisit.client.data.InventorySnapshot;
import red.jackf.whereisit.client.data.WorldCoordinate;

import java.util.ArrayList;
import java.util.List;

/**
 * Subscribes to NeoForge client events and feeds {@link InventoryLedger}.
 *
 * <p>Implements the well-known Chest-Tracker pattern:</p>
 * <ol>
 *   <li>{@link PlayerInteractEvent.RightClickBlock} → cache last interacted container position.</li>
 *   <li>{@link ScreenEvent.Init.Post} → if a container screen opens, capture an open-time snapshot.</li>
 *   <li>{@link ScreenEvent.Closing} → snapshot final contents and write to the ledger.</li>
 * </ol>
 *
 * <p>Tick-driven safety net handles screens that close without firing the event (death,
 * server kick, dimension change).</p>
 */
@EventBusSubscriber(modid = WhereIsIt.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ContainerObserver {
    private ContainerObserver() {}

    /** Cached last right-clicked container block (single-slot interaction tracker). */
    private static @Nullable CachedInteraction lastInteraction = null;

    /** Active screen session (set on screen open, consumed on close). */
    private static @Nullable ScreenSession session = null;

    private record CachedInteraction(ResourceKey<Level> dimension, BlockPos pos, BlockState state) {}

    private record ScreenSession(AbstractContainerMenu menu,
                                 ResourceKey<Level> dimension,
                                 BlockPos pos,
                                 @Nullable BlockState state,
                                 List<ItemStack> openSnapshot) {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) return;
        BlockPos pos = event.getPos();
        if (event.getLevel().getBlockEntity(pos) instanceof BaseContainerBlockEntity) {
            lastInteraction = new CachedInteraction(
                    event.getLevel().dimension(),
                    pos.immutable(),
                    event.getLevel().getBlockState(pos)
            );
        }
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;
        if (isBlacklisted(screen)) { lastInteraction = null; return; }

        AbstractContainerMenu menu = screen.getMenu();
        ResourceKey<Level> dim;
        BlockPos pos;
        BlockState state;

        if (lastInteraction != null) {
            dim = lastInteraction.dimension;
            pos = lastInteraction.pos;
            state = lastInteraction.state;
        } else {
            // No interaction recorded → safe to ignore; we record only containers the player explicitly opened.
            return;
        }
        lastInteraction = null;

        session = new ScreenSession(menu, dim, pos, state, getItems(menu));
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;
        if (session == null || session.menu != screen.getMenu()) return;
        flush(session);
        session = null;
    }

    /**
     * Tick-driven safety net: invoked once per client tick from the top-level dispatcher.
     * Detects screens that closed without firing {@link ScreenEvent.Closing}.
     */
    public static void tick() {
        if (session == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            session = null;
            lastInteraction = null;
            return;
        }
        if (mc.screen instanceof AbstractContainerScreen<?> open && open.getMenu() == session.menu) {
            return; // still open; nothing to do
        }
        flush(session);
        session = null;
    }

    /**
     * Wipe transient state (used on disconnect / dimension change).
     */
    public static void reset() {
        lastInteraction = null;
        session = null;
    }

    // ---------- internals ----------

    private static void flush(ScreenSession s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        List<ItemStack> contents = getItems(s.menu);
        BlockState state = s.state != null ? s.state : mc.level.getBlockState(s.pos);

        // Canonicalise double-chest position via ConnectedBlocksGrabber
        BlockPos primary = s.pos;
        List<WorldCoordinate> connected = List.of();
        if (state != null) {
            List<BlockPos> linked = ConnectedBlocksGrabber.getConnected(mc.level, state, s.pos);
            if (!linked.isEmpty()) {
                primary = linked.get(0).immutable();
                if (linked.size() > 1) {
                    List<WorldCoordinate> rest = new ArrayList<>(linked.size() - 1);
                    for (int i = 1; i < linked.size(); i++) {
                        rest.add(WorldCoordinate.of(s.dimension, linked.get(i)));
                    }
                    connected = rest;
                }
            }
        }

        WorldCoordinate key = WorldCoordinate.of(s.dimension, primary);
        InventorySnapshot snap = InventorySnapshot.of(contents);
        long tick = mc.level.getGameTime();
        InventoryLedger.get().record(key, connected, snap, null, tick);
    }

    private static List<ItemStack> getItems(AbstractContainerMenu menu) {
        List<ItemStack> items = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) continue;
            if (slot.hasItem()) items.add(slot.getItem().copy());
        }
        return items;
    }

    private static boolean isBlacklisted(AbstractContainerScreen<?> screen) {
        return screen instanceof BeaconScreen
                || screen instanceof CartographyTableScreen
                || screen instanceof EnchantmentScreen
                || screen instanceof GrindstoneScreen
                || screen instanceof LoomScreen
                || screen instanceof StonecutterScreen
                || screen instanceof EffectRenderingInventoryScreen;
    }
}
