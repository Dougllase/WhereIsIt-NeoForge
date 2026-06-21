package red.jackf.whereisit.client.data;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import red.jackf.whereisit.api.SearchResult;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Client-side, render-oriented projection of one or more {@link SearchResult}s pointing at
 * the same primary {@link BlockPos}.
 *
 * Two server results at the same {@code primary} are merged: connected positions are unioned,
 * and the first non-null label is retained.
 */
public final class ClientSearchResult {
    private final BlockPos primary;
    private final Set<BlockPos> connected;
    private final @Nullable ItemStack representative;
    private final @Nullable Component label;
    private final @Nullable Vec3 labelOffset;

    private ClientSearchResult(BlockPos primary,
                               Set<BlockPos> connected,
                               @Nullable ItemStack representative,
                               @Nullable Component label,
                               @Nullable Vec3 labelOffset) {
        this.primary = primary.immutable();
        this.connected = Set.copyOf(connected);
        this.representative = representative;
        this.label = label;
        this.labelOffset = labelOffset;
    }

    public static ClientSearchResult fromApi(SearchResult api) {
        Objects.requireNonNull(api, "api");
        return new ClientSearchResult(
                api.pos(),
                new HashSet<>(api.otherPositions()),
                api.item(),
                api.name(),
                api.customNameOffset()
        );
    }

    /**
     * Merge {@code other} into this result. Returns a new instance.
     * - {@code primary} is preserved.
     * - Connected positions are unioned.
     * - Representative item and label fall back to {@code other}'s values only when this one is null.
     */
    public ClientSearchResult merge(ClientSearchResult other) {
        if (!primary.equals(other.primary)) {
            throw new IllegalArgumentException("Cannot merge results with different primary positions");
        }
        Set<BlockPos> union = new HashSet<>(connected);
        union.addAll(other.connected);
        union.remove(primary);

        ItemStack repr = this.representative != null ? this.representative : other.representative;
        Component lbl = this.label != null ? this.label : other.label;
        Vec3 offset = this.labelOffset != null ? this.labelOffset : other.labelOffset;
        return new ClientSearchResult(primary, union, repr, lbl, offset);
    }

    public BlockPos primary() {
        return primary;
    }

    public Set<BlockPos> connected() {
        return connected;
    }

    public List<BlockPos> allPositions() {
        List<BlockPos> all = new java.util.ArrayList<>(connected.size() + 1);
        all.add(primary);
        all.addAll(connected);
        return all;
    }

    public @Nullable ItemStack representative() {
        return representative;
    }

    public @Nullable Component label() {
        return label;
    }

    public Vec3 labelOffset() {
        if (labelOffset != null) return labelOffset;
        if (connected.isEmpty()) return new Vec3(0, 1, 0);
        Vec3 base = Vec3.atLowerCornerOf(primary);
        Vec3 acc = base;
        for (BlockPos p : connected) {
            acc = acc.add(Vec3.atLowerCornerOf(p));
        }
        double factor = 1.0 / (connected.size() + 1);
        return acc.multiply(factor, factor, factor).subtract(base).add(0, 1, 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClientSearchResult that)) return false;
        return primary.equals(that.primary)
                && connected.equals(that.connected)
                && Objects.equals(representative, that.representative)
                && Objects.equals(label, that.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(primary, connected, representative, label);
    }
}
