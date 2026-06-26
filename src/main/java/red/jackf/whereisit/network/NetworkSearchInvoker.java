package red.jackf.whereisit.network;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.SearchResult;
import red.jackf.whereisit.client.api.events.SearchInvoker;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * Registers a {@link SearchInvoker} that forwards searches to the server via
 * {@link C2SSearchRequest} when the player is connected to a remote (logical) server.
 *
 * <p>Registered by {@code SearchInvokerDefaults} so it coexists with the local
 * client-side invoker. Only fires for non-integrated-server connections.</p>
 */
public final class NetworkSearchInvoker {
    private NetworkSearchInvoker() {}

    private static Consumer<Collection<SearchResult>> pendingConsumer;

    public static void setup() {
        SearchInvoker.EVENT.register((request, resultConsumer) -> {
            var connection = Minecraft.getInstance().getConnection();
            // Only forward to server when connected to a dedicated or LAN server
            // (integrated server already has everything loaded client-side).
            if (connection == null || Minecraft.getInstance().hasSingleplayerServer()) {
                return false;
            }

            pendingConsumer = resultConsumer;
            PacketDistributor.sendToServer(new C2SSearchRequest(request));
            return true;
        });
    }

    /** Called by {@link S2CSearchResults#handle} to feed results back to the consumer. */
    static void deliverResults(Collection<SearchResult> results) {
        var consumer = pendingConsumer;
        pendingConsumer = null;
        if (consumer != null) {
            consumer.accept(results);
        }
    }
}
