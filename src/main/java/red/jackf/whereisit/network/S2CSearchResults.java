package red.jackf.whereisit.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.api.SearchResult;

import java.util.*;

/**
 * Server → Client: delivers a batch of {@link SearchResult}s to the requesting player.
 */
public record S2CSearchResults(Collection<SearchResult> results) implements CustomPacketPayload {
    public static final Type<S2CSearchResults> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WhereIsIt.MODID, "search_results"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CSearchResults> STREAM_CODEC =
            StreamCodec.composite(
                    SearchResult.STREAM_CODEC.apply(ByteBufCodecs.collection(LinkedHashSet::new)),
                    S2CSearchResults::results,
                    S2CSearchResults::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handle the payload on the client. */
    public static void handle(final S2CSearchResults payload, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            NetworkSearchInvoker.deliverResults(new ArrayList<>(payload.results()));
        });
    }
}
