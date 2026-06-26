package red.jackf.whereisit.client.defaults;

import net.minecraft.client.Minecraft;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.SearchResult;
import red.jackf.whereisit.client.api.events.SearchInvoker;
import red.jackf.whereisit.client.data.ContainerRecord;
import red.jackf.whereisit.client.tracking.ContainerTracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * Answers searches from the client-side container records instead of asking the server. This is the NeoForge port's
 * replacement for the original network-based SearchInvokerDefaults.
 */
public class SearchInvokerDefaults {
    static void setup() {
        SearchInvoker.EVENT.register((request, resultConsumer) -> {
            var level = Minecraft.getInstance().level;
            if (level == null) return false;

            Collection<SearchResult> found = new ArrayList<>();
            for (ContainerRecord record : ContainerTracker.getRecords(level.dimension())) {
                var snapshot = record.snapshot();
                if (snapshot == null || snapshot.isEmpty()) continue;
                for (var stack : snapshot.stacks()) {
                    if (SearchRequest.check(stack, request)) {
                        found.add(SearchResult.builder(record.key().pos()).item(stack).build());
                        break; // one match per container is enough for highlighting
                    }
                }
            }
            resultConsumer.accept(found);
            return true; // a search was started (records were consulted)
        });
    }
}
