package red.jackf.whereisit.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import red.jackf.whereisit.WhereIsIt;

/**
 * Registers custom payload types with the NeoForge network channel.
 */
@SuppressWarnings("deprecation")
@EventBusSubscriber(modid = WhereIsIt.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class NetworkRegistration {
    private NetworkRegistration() {}

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(WhereIsIt.MODID).versioned("1.0");

        // C2S: Player requests a search on the server.
        registrar.playToServer(
                C2SSearchRequest.TYPE,
                C2SSearchRequest.STREAM_CODEC,
                C2SSearchRequest::handle
        );

        // S2C: Server sends search results back to the client.
        registrar.playToClient(
                S2CSearchResults.TYPE,
                S2CSearchResults.STREAM_CODEC,
                S2CSearchResults::handle
        );
    }
}
