package red.jackf.whereisit.client.tracking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BeaconScreen;
import net.minecraft.client.gui.screens.inventory.CartographyTableScreen;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.client.gui.screens.inventory.LoomScreen;
import net.minecraft.client.gui.screens.inventory.StonecutterScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.api.search.ConnectedBlocksGrabber;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * Tracks containers the player opens and snapshots their contents when the screen closes, so searches can be answered
 * locally without any server-side mod.
 *
 * <p><b>Architecture (directly modelled after Chest Tracker):</b></p>
 * <pre>
 *   Player right-clicks container
 *     → onRightClickBlock() caches position + block state in lastInteraction (InteractionTracker pattern)
 *
 *   Screen opens (BEFORE_INIT equivalent: ScreenEvent.Init.Post)
 *     → onScreenOpen() consumes lastInteraction, stores in currentSession
 *     → Registers ScreenEvent.Closing callback for this specific screen instance
 *
 *   Screen closes (ScreenEvent.Closing, equivalent to ScreenEvents.remove(screen))
 *     → onScreenClosing() snapshots container contents via getItems() (excludes player slots)
 *     → Canonicalizes double-chest position via ConnectedBlocksGrabber
 *     → Stores/updates ContainerRecord
 *     → Clears lastInteraction (InteractionTracker.clear() pattern)
 * </pre>
 *
 * <p>Records are persisted per-world under {@code config/whereisit/records/<worldId>.dat}.</p>
 */
@EventBusSubscriber(modid = WhereIsIt.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ContainerTracker {

    // ======== Constants ========

    private static final int AUTOSAVE_TICKS = 600; // 30s

    /** How often (in ticks) to scan nearby containers for validity. 200 ticks ≈ 10 seconds. */
    private static final int CLEANUP_SCAN_TICKS = 200;

    /** Range (in blocks) from the player to scan for stale records. */
    private static final int CLEANUP_SCAN_RANGE = 32;

    // ======== Interaction Tracker (Chest Tracker: InteractionTrackerImpl) ========

    /**
     * The last block the player right-clicked. Set by {@link #onRightClickBlock}, consumed by {@link #onScreenOpen}.
     * <p>Mirrors Chest Tracker's {@code InteractionTrackerImpl.lastSource}.</p>
     */
    private static CachedBlockSource lastInteraction = null;

    /**
     * Caches level, position and block state at the time of interaction.
     * <p>Mirrors Chest Tracker's {@code CachedClientBlockSource} (implements {@code ClientBlockSource}).</p>
     * <p>Unlike the lazy-only version, this captures the Level reference at construction time to avoid
     * issues if the player changes dimensions between interaction and screen close.</p>
     */
    private static class CachedBlockSource {
        final Level level;
        final ResourceKey<Level> dimension;
        final BlockPos pos;
        final BlockState state;

        CachedBlockSource(Level level, BlockPos pos) {
            this.level = level;
            this.dimension = level.dimension();
            this.pos = pos.immutable();
            this.state = level.getBlockState(pos);
        }

        CachedBlockSource(Level level, BlockPos pos, BlockState state) {
            this.level = level;
            this.dimension = level.dimension();
            this.pos = pos.immutable();
            this.state = state;
        }
    }

    // ======== Screen Session (Chest Tracker: ScreenOpenContext + ScreenCloseContext) ========

    /**
     * Tracks the currently-open container session.
     * <p>Mirrors Chest Tracker's {@code ScreenOpenContextImpl} + {@code ScreenCloseContextImpl} lifecycle.</p>
     */
    private static ScreenSession currentSession = null;

    private static class ScreenSession {
        final AbstractContainerMenu menu;
        final BlockPos pos;
        final ResourceKey<Level> dimension;
        final BlockState cachedState;
        final int containerId;
        /** Snapshot of container contents when the screen was opened. Used to compute collection diff on close. */
        final List<ItemStack> openContents;

        ScreenSession(AbstractContainerMenu menu, BlockPos pos, ResourceKey<Level> dimension,
                      BlockState cachedState, int containerId, List<ItemStack> openContents) {
            this.menu = menu;
            this.pos = pos;
            this.dimension = dimension;
            this.cachedState = cachedState;
            this.containerId = containerId;
            this.openContents = openContents;
        }
    }

    // ======== Records ========

    private static final Map<ResourceKey<Level>, Map<BlockPos, ContainerRecord>> RECORDS = new HashMap<>();

    // Collection tracking: counts items taken from containers via snapshot diff.
    private static final Map<Item, CollectionData> COLLECTED = new HashMap<>();

    public record CollectionData(int count, int containerCount) {}

    // ======== Persistence state ========

    private static String currentWorldId = null;
    private static boolean dirty = false;
    private static int autosaveCounter = 0;
    private static HolderLookup.Provider cachedRegistries = null;

    // ======== Cleanup scan state ========

    private static int cleanupScanCounter = 0;

    // ======== Real-time update hash ========

    private static long lastContentHash = Long.MIN_VALUE;

    // ======== Blacklisted screens (Chest Tracker: ScreenBlacklist) ========

    /**
     * Tests whether a screen class should be excluded from container tracking.
     * <p>Mirrors Chest Tracker's {@code ScreenBlacklist}.</p>
     */
    private static boolean isBlacklisted(AbstractContainerScreen<?> screen) {
        return screen instanceof BeaconScreen
                || screen instanceof CartographyTableScreen
                || screen instanceof EnchantmentScreen
                || screen instanceof GrindstoneScreen
                || screen instanceof LoomScreen
                || screen instanceof StonecutterScreen
                || screen instanceof EffectRenderingInventoryScreen;
    }

    // ======== Event Handlers ========

    /**
     * Records the last right-clicked block.
     * <p>Mirrors Chest Tracker's {@code UseBlockCallback → InteractionTrackerImpl.setLastBlockSource()}.</p>
     * <p>Only caches if the block has a {@link BaseContainerBlockEntity} (chest, barrel, shulker, etc.).</p>
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) return;
        var pos = event.getPos();
        if (event.getLevel().getBlockEntity(pos) instanceof BaseContainerBlockEntity) {
            // Cache level reference + position + block state at time of interaction
            // (Chest Tracker: InteractionTrackerImpl.setLastBlockSource(new CachedClientBlockSource(level, pos)))
            lastInteraction = new CachedBlockSource(event.getLevel(), pos);
            WhereIsIt.LOGGER.debug("ContainerTracker: interaction at {}", pos);
        }
    }

    /**
     * Associates the open container screen with the last interacted position.
     * <p>Mirrors Chest Tracker's {@code ScreenEvents.BEFORE_INIT → DefaultProvider.onScreenOpen()}.</p>
     * <p>Consumes {@link #lastInteraction} (Chest Tracker pattern: get then clear).</p>
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;

        // Chest Tracker: ScreenBlacklist check — skip blacklisted screens
        if (isBlacklisted(screen)) {
            lastInteraction = null;
            return;
        }

        AbstractContainerMenu menu = screen.getMenu();
        int containerId = menu.containerId;

        BlockPos pos = null;
        ResourceKey<Level> dim = null;
        BlockState state = null;

        // Primary: use the last right-clicked block (Chest Tracker: InteractionTracker.getLastBlockSource())
        if (lastInteraction != null) {
            pos = lastInteraction.pos;
            dim = lastInteraction.dimension;
            state = lastInteraction.state;
            WhereIsIt.LOGGER.debug("ContainerTracker: screen init, pos={}, containerId={}", pos, containerId);
        } else {
            // Fallback: scan nearby blocks for a container (Chest Tracker does not have this;
            // it relies entirely on InteractionTracker. We add this as a safety measure.)
            pos = findNearbyContainer();
            if (pos != null) {
                var mc = Minecraft.getInstance();
                dim = mc.level != null ? mc.level.dimension() : null;
                state = mc.level != null ? mc.level.getBlockState(pos) : null;
                WhereIsIt.LOGGER.debug("ContainerTracker: screen init (fallback), pos={}, containerId={}", pos, containerId);
            }
        }

        // Consume the interaction (Chest Tracker: InteractionTracker.clear() after use)
        lastInteraction = null;

        if (pos != null && dim != null) {
            // Snapshot container contents when the screen opens.
            // This is the baseline for computing collection diff when the screen closes,
            // so that items taken by the player are correctly tracked even on first open.
            List<ItemStack> openContents = getItems(menu);
            currentSession = new ScreenSession(menu, pos, dim, state, containerId, openContents);
        }
    }

    /**
     * Records container contents when the screen closes.
     * <p>Mirrors Chest Tracker's {@code ScreenEvents.remove(screen) → DefaultProvider.onScreenClose()}.</p>
     * <p>Uses {@link #getItems(AbstractContainerMenu)} to snapshot non-empty, non-player items,
     * then canonicalizes double-chest positions via {@link ConnectedBlocksGrabber}.</p>
     */
    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;

        AbstractContainerMenu menu = screen.getMenu();

        // Chest Tracker: check if this is the screen we're tracking
        if (currentSession != null && currentSession.menu == menu) {
            WhereIsIt.LOGGER.debug("ContainerTracker: screen closing, recording pos={}, containerId={}",
                    currentSession.pos, currentSession.containerId);
            recordContainer(currentSession.dimension, currentSession.pos, currentSession.menu,
                    currentSession.cachedState, currentSession.openContents);
            currentSession = null;
            lastContentHash = Long.MIN_VALUE;

            // Chest Tracker: InteractionTrackerImpl.INSTANCE.clear() after recording
            lastInteraction = null;
            return;
        }

        // Safety net: screen closed but we don't have a matching session
        BlockPos fallbackPos = findNearbyContainer();
        if (fallbackPos != null) {
            var mc = Minecraft.getInstance();
            if (mc.level != null) {
                WhereIsIt.LOGGER.debug("ContainerTracker: screen closing (safety net), pos={}, containerId={}",
                        fallbackPos, menu.containerId);
                recordContainer(mc.level.dimension(), fallbackPos, menu, null, null);
            }
        }

        currentSession = null;
        lastContentHash = Long.MIN_VALUE;
        lastInteraction = null;
    }

    // ======== Tick-based Detection (safety net for server-forced close / teleport / death) ========

    /**
     * Called every client tick. Detects when a container screen has been closed and records contents.
     * <p>This handles cases where {@code ScreenEvent.Closing} doesn't fire (server-forced close, teleport, death, etc.).</p>
     * <p>Also updates open container contents in real-time (e.g. hopper transfers, other players modifying).</p>
     */
    public static void tick() {
        if (currentSession == null) return;

        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            currentSession = null;
            lastInteraction = null;
            return;
        }

        // Check if the container screen is still open with the same menu
        if (mc.screen instanceof AbstractContainerScreen<?> screen
                && screen.getMenu() == currentSession.menu) {
            // Container still open — update contents in real-time
            updateOpenContainer();
            return;
        }

        // Container screen has been closed — record contents
        WhereIsIt.LOGGER.debug("ContainerTracker: tick detected close, pos={}, containerId={}",
                currentSession.pos, currentSession.containerId);
        recordContainer(currentSession.dimension, currentSession.pos, currentSession.menu,
                currentSession.cachedState, currentSession.openContents);
        currentSession = null;
        lastContentHash = Long.MIN_VALUE;
        lastInteraction = null;
    }

    /** Update the stored record for a currently-open container (real-time content sync). */
    private static void updateOpenContainer() {
        if (currentSession == null) return;
        long hash = computeContentHash(currentSession.menu);
        if (hash == lastContentHash) return;
        lastContentHash = hash;

        List<ItemStack> contents = getItems(currentSession.menu);
        Map<BlockPos, ContainerRecord> dimRecords = RECORDS.computeIfAbsent(currentSession.dimension, d -> new HashMap<>());
        dimRecords.put(currentSession.pos.immutable(),
                new ContainerRecord(currentSession.dimension, currentSession.pos.immutable(), contents, currentTick()));
        dirty = true;
    }

    // ======== Periodic Cleanup Scan ========

    /**
     * Called every client tick. Periodically scans the area around the player and removes records for
     * containers that no longer exist (destroyed, replaced by another block, etc.).
     * <p>This handles cases where:</p>
     * <ul>
     *   <li>A container is broken by any player (or explosion, piston, etc.)</li>
     *   <li>A container block is replaced by another block</li>
     *   <li>A container is moved (e.g. by a mod or piston)</li>
     * </ul>
     * <p>Also refreshes records for containers that still exist but may have been modified by other players
     * (since we only snapshot on close, external changes would be missed).</p>
     */
    public static void tickCleanupScan() {
        if (++cleanupScanCounter < CLEANUP_SCAN_TICKS) return;
        cleanupScanCounter = 0;

        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        BlockPos playerPos = mc.player.blockPosition();
        double rangeSq = (double) CLEANUP_SCAN_RANGE * (double) CLEANUP_SCAN_RANGE;

        for (Map.Entry<ResourceKey<Level>, Map<BlockPos, ContainerRecord>> dimEntry : RECORDS.entrySet()) {
            // Only scan the dimension the player is currently in.
            // Use equals() rather than reference equality: while ResourceKey is normally interned via the
            // ResourceKey pool, that contract is not guaranteed across all loading scenarios (e.g. cross-mod
            // datapack reload) and reference comparison would silently skip cleanup if interning ever breaks.
            if (!dimEntry.getKey().equals(mc.level.dimension())) continue;

            Map<BlockPos, ContainerRecord> dimRecords = dimEntry.getValue();
            if (dimRecords.isEmpty()) continue;

            // Collect positions to remove (can't remove while iterating)
            List<BlockPos> toRemove = new ArrayList<>();

            for (Map.Entry<BlockPos, ContainerRecord> recordEntry : dimRecords.entrySet()) {
                BlockPos pos = recordEntry.getKey();

                // Only check records within scan range
                double distSq = Vec3.atCenterOf(pos).distanceToSqr(playerPos.getX() + 0.5, playerPos.getY() + 0.5, playerPos.getZ() + 0.5);
                if (distSq > rangeSq) continue;

                // Check if the block at this position is still a container
                if (!(mc.level.getBlockEntity(pos) instanceof BaseContainerBlockEntity)) {
                    // Container no longer exists — mark for removal
                    toRemove.add(pos);
                    WhereIsIt.LOGGER.debug("ContainerTracker: cleanup removing stale record at {} (block is no longer a container)", pos);
                }
                // Note: We don't refresh contents here because the container screen isn't open.
                // Contents will be refreshed next time the player opens the container.
            }

            if (!toRemove.isEmpty()) {
                for (BlockPos pos : toRemove) {
                    dimRecords.remove(pos);
                }
                dirty = true;
            }
        }
    }

    // ======== Content Snapshotting (Chest Tracker: ScreenCloseContext) ========

    /**
     * Tests whether a slot is part of the player's inventory.
     * <p>Exactly mirrors Chest Tracker's {@code ProviderUtils.isPlayerSlot()}.</p>
     *
     * @param slot Slot to test
     * @return Whether the given slot is backed by the player's inventory
     */
    private static boolean isPlayerSlot(Slot slot) {
        return slot.container instanceof Inventory;
    }

    /**
     * Returns all non-empty, non-player inventory stacks in the slots of this menu.
     * <p>Exactly mirrors Chest Tracker's {@code ScreenCloseContext.getItems()}.</p>
     *
     * @param menu The container menu to snapshot
     * @return All non-empty ItemStacks in the container slots
     */
    private static List<ItemStack> getItems(AbstractContainerMenu menu) {
        List<ItemStack> items = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (isPlayerSlot(slot)) continue;
            if (slot.hasItem()) {
                items.add(slot.getItem().copy());
            }
        }
        return items;
    }

    private static long computeContentHash(AbstractContainerMenu menu) {
        long hash = 0;
        for (Slot slot : menu.slots) {
            if (isPlayerSlot(slot)) continue;
            if (slot.hasItem()) {
                hash = hash * 31 + slot.getItem().getCount();
                hash = hash * 31 + Item.getId(slot.getItem().getItem());
            }
        }
        return hash;
    }

    // ======== Recording (Chest Tracker: DefaultProviderScreenClose → MemoryBuilder) ========

    /**
     * Snapshots and records a container's contents.
     * <p>Mirrors Chest Tracker's {@code DefaultProviderScreenClose → MemoryBuilder.create(context.getItems()) → bank.addMemory()}.</p>
     * <p>Canonicalizes double-chest positions via {@link ConnectedBlocksGrabber} (Chest Tracker pattern in
     * {@code DefaultProviderMemoryLocation.EVENT}).</p>
     */
    /**
     * Record container contents, computing collection diff against the baseline (open-time snapshot).
     *
     * @param dimension   Dimension of the container
     * @param pos         Block position
     * @param menu        The container menu (for snapshotting current contents)
     * @param cachedState Cached block state from interaction time
     * @param openContents Snapshot of contents when the screen was opened; used as the baseline for diff.
     *                     If null, falls back to the previous record (may miss first-open diffs).
     */
    private static void recordContainer(ResourceKey<Level> dimension, BlockPos pos, AbstractContainerMenu menu,
                                        BlockState cachedState, List<ItemStack> openContents) {
        // Step 1: Snapshot current items (Chest Tracker: context.getItems())
        List<ItemStack> contents = getItems(menu);

        // Step 2: Update registry access for serialization
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level != null) {
            cachedRegistries = level.registryAccess();
        }

        // Step 3: Canonicalize connected blocks (Chest Tracker: ConnectedBlocksGrabber in defaultMemoryCreator)
        BlockState state = cachedState;
        if (state == null && level != null) {
            state = level.getBlockState(pos);
        }
        // Only canonicalize via ConnectedBlocksGrabber when the loaded level actually matches the
        // dimension we are recording for. If the player switched dimensions between opening the
        // container and the screen closing (e.g. portal teleport before the close packet was processed),
        // querying block state from mc.level would resolve against the *wrong* world and corrupt the
        // root-position calculation. Fall back to the raw position in that edge case.
        if (state != null && level != null && dimension.equals(level.dimension())) {
            List<BlockPos> connected = ConnectedBlocksGrabber.getConnected(level, state, pos);
            if (connected != null && !connected.isEmpty()) {
                pos = connected.get(0); // root position (Chest Tracker pattern: connectedBlocks.get(0))
            }
        }

        BlockPos finalPos = pos.immutable();
        Map<BlockPos, ContainerRecord> dimRecords = RECORDS.computeIfAbsent(dimension, d -> new HashMap<>());

        // Step 4: Compute collection diff (Chest Tracker: counting items after screen close)
        // Use openContents as the baseline — this correctly tracks items taken on first open too.
        // Falls back to oldRecord if openContents is null (shouldn't happen in normal flow).
        List<ItemStack> baseline = (openContents != null) ? openContents : null;
        if (baseline == null) {
            ContainerRecord oldRecord = dimRecords.get(finalPos);
            if (oldRecord != null) baseline = oldRecord.contents();
        }
        if (baseline != null) {
            computeCollectionDiff(baseline, contents);
        }

        // Step 5: Store record (Chest Tracker: bank.addMemory(key, pos, memory))
        dimRecords.put(finalPos, new ContainerRecord(dimension, finalPos, contents, currentTick()));
        dirty = true;

        WhereIsIt.LOGGER.debug("ContainerTracker: recorded {} items at {} (dim={})",
                contents.size(), finalPos, dimension.location());
    }

    /**
     * Computes the difference between old and new container contents to track items taken by the player.
     * <p>Mirrors Chest Tracker's counting logic in {@code DefaultProviderScreenClose}.</p>
     */
    private static void computeCollectionDiff(List<ItemStack> oldContents, List<ItemStack> newContents) {
        Map<Item, Integer> oldCounts = new HashMap<>();
        for (ItemStack stack : oldContents) {
            if (!stack.isEmpty()) {
                oldCounts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        Map<Item, Integer> newCounts = new HashMap<>();
        for (ItemStack stack : newContents) {
            if (!stack.isEmpty()) {
                newCounts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        for (Map.Entry<Item, Integer> entry : oldCounts.entrySet()) {
            Item item = entry.getKey();
            int oldCount = entry.getValue();
            int newCount = newCounts.getOrDefault(item, 0);
            if (newCount < oldCount) {
                int taken = oldCount - newCount;
                CollectionData existing = COLLECTED.get(item);
                if (existing == null) {
                    COLLECTED.put(item, new CollectionData(taken, 1));
                } else {
                    COLLECTED.put(item, new CollectionData(existing.count() + taken, existing.containerCount() + 1));
                }
            }
        }
    }

    // ======== Position Estimation (fallback only) ========

    /**
     * Find a container block near the player. Searches a 5x5x5 area around the player's position.
     * <p>This is a fallback only — the primary path uses InteractionTracker (lastInteraction).</p>
     */
    private static BlockPos findNearbyContainer() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;

        BlockPos playerPos = mc.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(playerPos.offset(-2, -2, -2), playerPos.offset(2, 2, 2))) {
            if (mc.level.getBlockEntity(pos) instanceof BaseContainerBlockEntity) {
                return pos.immutable();
            }
        }
        return null;
    }

    // ======== Public API ========

    public static Map<Item, CollectionData> getCollectionData() {
        return Map.copyOf(COLLECTED);
    }

    public static void clearCollectionData(Item item) {
        COLLECTED.remove(item);
    }

    public static void clearAllCollectionData() {
        COLLECTED.clear();
    }

    public static Collection<ContainerRecord> getRecords(ResourceKey<Level> dimension) {
        Map<BlockPos, ContainerRecord> dim = RECORDS.get(dimension);
        return dim == null ? List.of() : new ArrayList<>(dim.values());
    }

    public static void clearAll() {
        RECORDS.clear();
        COLLECTED.clear();
    }

    // ======== Lifecycle ========

    public static void onJoinWorld() {
        currentWorldId = computeWorldId();
        if (currentWorldId != null) loadFromDisk(currentWorldId);
    }

    public static void onLeaveWorld() {
        saveIfDirty();
        clearAll();
        currentWorldId = null;
        cachedRegistries = null;
        dirty = false;
        currentSession = null;
        lastInteraction = null;
    }

    public static void tickAutosave() {
        if (currentWorldId == null) return;
        if (++autosaveCounter >= AUTOSAVE_TICKS) {
            saveIfDirty();
            autosaveCounter = 0;
        }
    }

    public static void saveIfDirty() {
        if (!dirty || currentWorldId == null) return;
        saveToDisk(currentWorldId);
        dirty = false;
    }

    // ======== Persistence ========

    private static String computeWorldId() {
        var mc = Minecraft.getInstance();
        var sp = mc.getSingleplayerServer();
        if (sp != null) {
            return "sp_" + sanitize(sp.getWorldData().getLevelName());
        }
        ServerData si = mc.getCurrentServer();
        if (si != null) {
            return "mp_" + sanitize(si.ip);
        }
        return null;
    }

    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "unknown";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Path recordsDir() {
        return FMLPaths.CONFIGDIR.get().resolve("whereisit/records");
    }

    private static void saveToDisk(String worldId) {
        HolderLookup.Provider registries = cachedRegistries;
        var mc = Minecraft.getInstance();
        if (registries == null && mc.level != null) registries = mc.level.registryAccess();
        if (registries == null) return;

        ListTag list = new ListTag();
        for (Map<BlockPos, ContainerRecord> dimRecords : RECORDS.values()) {
            for (ContainerRecord record : dimRecords.values()) {
                CompoundTag tag = new CompoundTag();
                tag.putString("dimension", record.dimension().location().toString());
                tag.putLong("pos", record.pos().asLong());
                tag.putLong("tick", record.gameTick());
                ListTag items = new ListTag();
                for (ItemStack stack : record.contents()) {
                    if (!stack.isEmpty()) items.add(stack.save(registries));
                }
                tag.put("contents", items);
                list.add(tag);
            }
        }
        CompoundTag root = new CompoundTag();
        root.put("records", list);

        ListTag collectedList = new ListTag();
        for (Map.Entry<Item, CollectionData> entry : COLLECTED.entrySet()) {
            CompoundTag ct = new CompoundTag();
            var itemKey = entry.getKey().builtInRegistryHolder().key();
            ct.putString("item", itemKey.location().toString());
            ct.putInt("count", entry.getValue().count());
            ct.putInt("containerCount", entry.getValue().containerCount());
            collectedList.add(ct);
        }
        root.put("collected", collectedList);

        try {
            Path dir = recordsDir();
            Files.createDirectories(dir);
            NbtIo.writeCompressed(root, dir.resolve(worldId + ".dat"));
        } catch (Exception e) {
            WhereIsIt.LOGGER.error("Failed to save whereisit container records for {}", worldId, e);
        }
    }

    private static void loadFromDisk(String worldId) {
        var mc = Minecraft.getInstance();
        HolderLookup.Provider registries = mc.level != null ? mc.level.registryAccess() : null;
        if (registries == null) return;
        Path file = recordsDir().resolve(worldId + ".dat");
        if (!Files.exists(file)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            ListTag list = root.getList("records", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("dimension")));
                BlockPos pos = BlockPos.of(tag.getLong("pos"));
                long tick = tag.getLong("tick");
                List<ItemStack> contents = new ArrayList<>();
                ListTag items = tag.getList("contents", Tag.TAG_COMPOUND);
                for (int j = 0; j < items.size(); j++) {
                    ItemStack stack = ItemStack.parseOptional(registries, items.getCompound(j));
                    if (!stack.isEmpty()) contents.add(stack);
                }
                RECORDS.computeIfAbsent(dim, d -> new HashMap<>()).put(pos, new ContainerRecord(dim, pos, contents, tick));
            }

            ListTag collectedList = root.getList("collected", Tag.TAG_COMPOUND);
            for (int i = 0; i < collectedList.size(); i++) {
                CompoundTag ct = collectedList.getCompound(i);
                String itemId = ct.getString("item");
                if (itemId.isEmpty()) continue;
                try {
                    ResourceLocation rl = ResourceLocation.parse(itemId);
                    var item = registries.lookupOrThrow(Registries.ITEM).get(ResourceKey.create(Registries.ITEM, rl)).orElse(null);
                    if (item != null) {
                        COLLECTED.put(item.value(), new CollectionData(ct.getInt("count"), ct.getInt("containerCount")));
                    }
                } catch (Exception ignored) {}
            }

            cachedRegistries = registries;
        } catch (Exception e) {
            WhereIsIt.LOGGER.error("Failed to load whereisit container records for {}", worldId, e);
        }
    }

    private static long currentTick() {
        var level = Minecraft.getInstance().level;
        return level != null ? level.getGameTime() : 0L;
    }
}
