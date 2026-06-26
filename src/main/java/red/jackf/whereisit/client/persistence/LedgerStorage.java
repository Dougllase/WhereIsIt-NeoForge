package red.jackf.whereisit.client.persistence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.client.data.ContainerRecord;
import red.jackf.whereisit.client.data.InventorySnapshot;
import red.jackf.whereisit.client.data.WorldCoordinate;
import red.jackf.whereisit.client.inventory.InventoryLedger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent NBT storage for the client-side {@link InventoryLedger}.
 *
 * <p>Stores observed container records across sessions in a single compressed NBT file next to
 * the world save (or the game directory for integrated servers). The file is loaded when the player
 * joins a world and saved periodically via autosave / on disconnect.</p>
 */
public final class LedgerStorage {
    private LedgerStorage() {}

    private static final String FILE_NAME = "whereisit_ledger.nbt";
    private static final String DIMENSIONS_KEY = "dimensions";
    private static final String DIMENSION_ID_KEY = "dimension";
    private static final String RECORDS_KEY = "records";
    private static final String POS_KEY = "pos";
    private static final String CONNECTED_KEY = "connected";
    private static final String ITEMS_KEY = "items";
    private static final String LABEL_KEY = "label";
    private static final String TICK_KEY = "tick";

    /** True if the in-memory ledger has changed since the last successful save. */
    private static volatile boolean dirty = false;

    public static void markDirty() {
        dirty = true;
    }

    public static boolean isDirty() {
        return dirty;
    }

    /**
     * Load persisted records into the global ledger.
     *
     * <p>Safe to call before registries are available: items/labels that cannot be resolved are
     * silently skipped rather than crashing.</p>
     */
    public static void load(HolderLookup.Provider provider) {
        InventoryLedger ledger = InventoryLedger.get();
        ledger.clearAll();

        Path file = getFilePath();
        if (!Files.exists(file)) {
            WhereIsIt.LOGGER.debug("LedgerStorage: no persisted ledger at {}", file);
            return;
        }

        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            ListTag dimensions = root.getList(DIMENSIONS_KEY, Tag.TAG_COMPOUND);
            int loaded = 0;

            for (int d = 0; d < dimensions.size(); d++) {
                CompoundTag dimTag = dimensions.getCompound(d);
                ResourceKey<Level> dimension = parseDimension(dimTag.getString(DIMENSION_ID_KEY));
                if (dimension == null) continue;

                ListTag records = dimTag.getList(RECORDS_KEY, Tag.TAG_COMPOUND);
                for (int r = 0; r < records.size(); r++) {
                    CompoundTag recTag = records.getCompound(r);
                    ContainerRecord record = readRecord(recTag, dimension, provider);
                    if (record != null) {
                        ledger.record(record.key(), record.connected(), record.snapshot(), record.label(), record.lastUpdatedTick());
                        loaded++;
                    }
                }
            }

            dirty = false;
            WhereIsIt.LOGGER.info("LedgerStorage: loaded {} records from {}", loaded, file);
        } catch (Exception e) {
            WhereIsIt.LOGGER.error("LedgerStorage: failed to load ledger from {}", file, e);
        }
    }

    /** Save the current ledger to disk if it is dirty. */
    public static void save(HolderLookup.Provider provider) {
        if (!dirty) return;
        saveForce(provider);
    }

    /** Save the current ledger to disk regardless of the dirty flag. */
    public static void saveForce(HolderLookup.Provider provider) {
        Path file = getFilePath();
        try {
            Files.createDirectories(file.getParent());
            CompoundTag root = writeLedger(InventoryLedger.get(), provider);
            NbtIo.writeCompressed(root, file);
            dirty = false;
            WhereIsIt.LOGGER.debug("LedgerStorage: saved ledger to {}", file);
        } catch (Exception e) {
            WhereIsIt.LOGGER.error("LedgerStorage: failed to save ledger to {}", file, e);
        }
    }

    /** Delete the persisted ledger file and clear the dirty flag. */
    public static void delete() {
        try {
            Files.deleteIfExists(getFilePath());
        } catch (IOException e) {
            WhereIsIt.LOGGER.error("LedgerStorage: failed to delete ledger file", e);
        }
        dirty = false;
    }

    private static Path getFilePath() {
        return FMLPaths.GAMEDIR.get().resolve("whereisit").resolve(FILE_NAME);
    }

    private static @Nullable ResourceKey<Level> parseDimension(String id) {
        try {
            return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id));
        } catch (Exception e) {
            WhereIsIt.LOGGER.warn("LedgerStorage: skipping unknown dimension id {}", id);
            return null;
        }
    }

    private static CompoundTag writeLedger(InventoryLedger ledger, HolderLookup.Provider provider) {
        CompoundTag root = new CompoundTag();
        ListTag dimensions = new ListTag();

        for (var dimEntry : ledger.debugSnapshot().entrySet()) {
            CompoundTag dimTag = new CompoundTag();
            dimTag.putString(DIMENSION_ID_KEY, dimEntry.getKey().location().toString());

            ListTag records = new ListTag();
            for (ContainerRecord record : dimEntry.getValue().values()) {
                records.add(writeRecord(record, provider));
            }
            dimTag.put(RECORDS_KEY, records);
            dimensions.add(dimTag);
        }

        root.put(DIMENSIONS_KEY, dimensions);
        return root;
    }

    private static CompoundTag writeRecord(ContainerRecord record, HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putLong(POS_KEY, record.key().pos().asLong());

        ListTag connected = new ListTag();
        for (WorldCoordinate wc : record.connected()) {
            connected.add(LongTag.valueOf(wc.pos().asLong()));
        }
        tag.put(CONNECTED_KEY, connected);

        ListTag items = new ListTag();
        for (ItemStack stack : record.snapshot().stacks()) {
            items.add(stack.saveOptional(provider));
        }
        tag.put(ITEMS_KEY, items);

        if (record.label() != null) {
            tag.putString(LABEL_KEY, Component.Serializer.toJson(record.label(), provider).toString());
        }

        tag.putLong(TICK_KEY, record.lastUpdatedTick());
        return tag;
    }

    private static @Nullable ContainerRecord readRecord(CompoundTag tag, ResourceKey<Level> dimension, HolderLookup.Provider provider) {
        try {
            BlockPos pos = BlockPos.of(tag.getLong(POS_KEY));
            WorldCoordinate key = WorldCoordinate.of(dimension, pos);

            ListTag connectedTag = tag.getList(CONNECTED_KEY, Tag.TAG_LONG);
            List<WorldCoordinate> connected = new ArrayList<>(connectedTag.size());
            for (int i = 0; i < connectedTag.size(); i++) {
                long encoded = ((LongTag) connectedTag.get(i)).getAsLong();
                connected.add(WorldCoordinate.of(dimension, BlockPos.of(encoded)));
            }

            ListTag itemsTag = tag.getList(ITEMS_KEY, Tag.TAG_COMPOUND);
            List<ItemStack> stacks = new ArrayList<>(itemsTag.size());
            for (int i = 0; i < itemsTag.size(); i++) {
                ItemStack stack = ItemStack.parseOptional(provider, itemsTag.getCompound(i));
                if (!stack.isEmpty()) stacks.add(stack);
            }
            InventorySnapshot snapshot = InventorySnapshot.of(stacks);

            Component label = null;
            if (tag.contains(LABEL_KEY, Tag.TAG_STRING)) {
                String labelJson = tag.getString(LABEL_KEY);
                label = Component.Serializer.fromJson(net.minecraft.util.GsonHelper.parse(labelJson, true), provider);
            }

            long tick = tag.getLong(TICK_KEY);
            return new ContainerRecord(key, connected, snapshot, label, tick);
        } catch (Exception e) {
            WhereIsIt.LOGGER.warn("LedgerStorage: failed to read record in {}", dimension.location(), e);
            return null;
        }
    }
}
