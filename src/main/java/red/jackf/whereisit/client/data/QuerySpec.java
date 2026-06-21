package red.jackf.whereisit.client.data;

import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;
import red.jackf.whereisit.api.SearchRequest;

import java.util.Objects;

/**
 * Immutable description of "what we are searching for". Wraps a {@link SearchRequest}
 * together with optional metadata used for routing and rendering.
 *
 * - {@code request} is the matcher applied to item stacks.
 * - {@code representativeItem} is the canonical Item used by the tracker manager to key
 *   per-item state; null for free-form requests.
 * - {@code label} is an optional human-readable description (e.g. "Diamond Pickaxe").
 */
public record QuerySpec(
        SearchRequest request,
        @Nullable Item representativeItem,
        @Nullable String label
) {
    public QuerySpec {
        Objects.requireNonNull(request, "request");
    }

    public static QuerySpec of(SearchRequest request) {
        return new QuerySpec(request, null, null);
    }

    public static QuerySpec ofItem(SearchRequest request, Item representative) {
        return new QuerySpec(request, representative, null);
    }
}
