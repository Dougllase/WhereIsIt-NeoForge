package red.jackf.whereisit.client.search;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.SearchResult;
import red.jackf.whereisit.client.data.ClientSearchResult;
import red.jackf.whereisit.client.data.ContainerRecord;
import red.jackf.whereisit.client.data.WorldCoordinate;
import red.jackf.whereisit.client.inventory.InventoryLedger;

import java.util.*;

/**
 * Pure-function search engine that queries the {@link InventoryLedger} and returns
 * deduplicated, merged {@link ClientSearchResult}s.
 *
 * <p>No side effects. No caching. Thread-safe to the extent that the ledger is.</p>
 */
public final class SearchPipeline {
    private SearchPipeline() {}

    /**
     * Execute a search against all known containers in the given dimension.
     *
     * @param request   the matcher to apply to every item stack
     * @param dimension the dimension whose ledger entries are searched
     * @return immutable, merged list of client results
     */
    public static List<ClientSearchResult> execute(SearchRequest request, ResourceKey<Level> dimension) {
        Collection<ContainerRecord> records = InventoryLedger.get().recordsIn(dimension);
        if (records.isEmpty()) return List.of();

        Map<BlockPos, ClientSearchResult> byPrimary = new LinkedHashMap<>(records.size());

        for (ContainerRecord record : records) {
            var snapshot = record.snapshot();
            if (snapshot == null || snapshot.isEmpty()) continue;

            for (ItemStack stack : snapshot.stacks()) {
                if (SearchRequest.check(stack, request)) {
                    SearchResult.Builder builder = SearchResult.builder(record.key().pos())
                            .item(stack.copy());

                    if (record.label() != null) {
                        builder.name(record.label(), null);
                    }

                    // Include connected positions (e.g. double chest halves)
                    List<BlockPos> connected = new ArrayList<>();
                    for (WorldCoordinate wc : record.connected()) {
                        connected.add(wc.pos());
                    }
                    if (!connected.isEmpty()) {
                        builder.otherPositions(connected);
                    }

                    ClientSearchResult csr = ClientSearchResult.fromApi(builder.build());
                    byPrimary.merge(csr.primary(), csr, ClientSearchResult::merge);
                    break; // one hit per container is enough for highlighting
                }
            }
        }

        return List.copyOf(byPrimary.values());
    }
}
