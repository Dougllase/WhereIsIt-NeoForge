package red.jackf.whereisit.gameplay;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.SearchResult;

import java.util.*;

/**
 * Shared utility for scanning loaded chunk block entities for item containers.
 *
 * <p>Works on the server side where {@code Capabilities.ItemHandler.BLOCK} is available.
 * Used by both network request handlers and potentially background scanning tasks.</p>
 */
public final class ContainerScanner {
    private ContainerScanner() {}

    /** Default search radius in chunks. */
    public static final int DEFAULT_CHUNK_RADIUS = 8;

    /**
     * Scan loaded chunks around a center position for containers whose items match
     * the given request. One result per container (first matching slot wins).
     *
     * @param level       target dimension
     * @param center      search center (typically player position)
     * @param request     what to look for; empty-stack fields are treated as wildcards
     * @param chunkRadius how many chunks in each cardinal direction to scan
     * @return immutable list of matching results, never null
     */
    public static List<SearchResult> scan(ServerLevel level,
                                          BlockPos center,
                                          SearchRequest request,
                                          int chunkRadius) {
        List<SearchResult> results = new ArrayList<>();
        int minCX = (center.getX() >> 4) - chunkRadius;
        int maxCX = (center.getX() >> 4) + chunkRadius;
        int minCZ = (center.getZ() >> 4) - chunkRadius;
        int maxCZ = (center.getZ() >> 4) + chunkRadius;

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
                if (chunk == null) continue;

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    IItemHandler handler = level.getCapability(
                            Capabilities.ItemHandler.BLOCK,
                            be.getBlockPos(),
                            be.getBlockState(),
                            be,
                            null);
                    if (handler == null) continue;

                    BlockPos pos = be.getBlockPos().immutable();
                    for (int slot = 0; slot < handler.getSlots(); slot++) {
                        ItemStack stack = handler.getStackInSlot(slot);
                        if (!stack.isEmpty() && SearchRequest.check(stack, request)) {
                            results.add(SearchResult.builder(pos)
                                    .item(stack.copy())
                                    .build());
                            break; // one match per container
                        }
                    }
                }
            }
        }
        return Collections.unmodifiableList(results);
    }

    /**
     * Shortcut using {@link #DEFAULT_CHUNK_RADIUS}.
     */
    public static List<SearchResult> scan(ServerLevel level, BlockPos center, SearchRequest request) {
        return scan(level, center, request, DEFAULT_CHUNK_RADIUS);
    }
}
