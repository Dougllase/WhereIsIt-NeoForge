package red.jackf.whereisit.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.SearchResult;
import red.jackf.whereisit.gameplay.ContainerScanner;

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
            List<SearchResult> results = ContainerScanner.scan(
                    player.serverLevel(), player.blockPosition(), payload.request());

            if (results.isEmpty()) return;

            // Deduplicate by position (ContainerScanner already returns one-per-container,
            // but double-chest halves may report the same inventory twice)
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
}
