package red.jackf.whereisit.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.SearchResult;

import java.util.*;

/**
 * Client → Server: the player wants to search loaded containers in the current dimension.
 */
public record C2SSearchRequest(SearchRequest request) implements CustomPacketPayload {
    public static final Type<C2SSearchRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WhereIsIt.MODID, "search_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SSearchRequest> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.fromCodecWithRegistriesTrusted(SearchRequest.CODEC),
                    C2SSearchRequest::request,
                    C2SSearchRequest::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handle the payload on the server. */
    public static void handle(final C2SSearchRequest payload, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) ctx.player();
            ServerLevel level = player.serverLevel();

            Collection<SearchResult> results = searchContainers(level, player.blockPosition(), payload.request());
            if (results.isEmpty()) return;

            Set<SearchResult> toSend = new LinkedHashSet<>();
            for (SearchResult r : results) {
                boolean merged = false;
                for (SearchResult existing : new ArrayList<>(toSend)) {
                    if (existing.pos().equals(r.pos())) {
                        toSend.remove(existing);
                        toSend.add(existing.withOtherPositions(new ArrayList<>(r.otherPositions())));
                        merged = true;
                        break;
                    }
                }
                if (!merged) toSend.add(r);
            }

            PacketDistributor.sendToPlayer(player, new S2CSearchResults(toSend));
        });
    }

    /** Scan loaded chunks for block entities with inventories matching the request. */
    private static Collection<SearchResult> searchContainers(ServerLevel level, BlockPos playerPos, SearchRequest request) {
        List<SearchResult> results = new ArrayList<>();

        // Search radius: 8 chunks (128 blocks) is reasonable for multiplayer
        int r = 8;

        for (int cx = (playerPos.getX() >> 4) - r; cx <= (playerPos.getX() >> 4) + r; cx++) {
            for (int cz = (playerPos.getZ() >> 4) - r; cz <= (playerPos.getZ() >> 4) + r; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
                if (chunk == null) continue;

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(),
                            be.getBlockState(), be, null);
                    if (handler == null) continue;

                    BlockPos pos = be.getBlockPos().immutable();
                    ItemStack matchedStack = ItemStack.EMPTY;

                    for (int slot = 0; slot < handler.getSlots(); slot++) {
                        ItemStack stack = handler.getStackInSlot(slot);
                        if (!stack.isEmpty() && SearchRequest.check(stack, request)) {
                            matchedStack = stack.copy();
                            break;
                        }
                    }
                    if (!matchedStack.isEmpty()) {
                        results.add(SearchResult.builder(pos).item(matchedStack).build());
                    }
                }
            }
        }
        return results;
    }
}
