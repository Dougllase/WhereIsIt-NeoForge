package red.jackf.whereisit.api.criteria;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import net.minecraft.resources.ResourceLocation;
import red.jackf.whereisit.WhereIsIt;

import java.util.HashMap;
import java.util.Map;

/**
 * Type object for a {@link Criterion}, pairing it with its serialization codec.
 *
 * <p>In the original Fabric version this used a Minecraft {@code Registry} (via FabricRegistryBuilder). The NeoForge
 * port keeps a simple client-side name → type map, since there is no server synchronization and no datapack integration
 * needed. {@link #byNameCodec()} still provides a dispatch codec identical in behaviour to the old registry codec.</p>
 */
public record CriterionType<T extends Criterion>(MapCodec<T> codec) {
    private static final Map<ResourceLocation, CriterionType<? extends Criterion>> BY_ID = new HashMap<>();
    private static final Map<CriterionType<?>, ResourceLocation> BY_TYPE = new HashMap<>();

    /**
     * Register a criterion type under the given id.
     */
    public static synchronized void register(ResourceLocation id, CriterionType<? extends Criterion> type) {
        BY_ID.put(id, type);
        BY_TYPE.put(type, id);
    }

    /**
     * A codec that resolves a {@link CriterionType} by its registered id, mirroring {@code Registry#byNameCodec()}.
     */
    public static Codec<CriterionType<?>> byNameCodec() {
        return ResourceLocation.CODEC.comapFlatMap(
                id -> {
                    var t = BY_ID.get(id);
                    return t != null ? DataResult.success(t) : DataResult.error(() -> "Unknown criterion type: " + id);
                },
                t -> BY_TYPE.getOrDefault(t, WhereIsIt.id("unknown"))
        );
    }

    public static <T extends Criterion> CriterionType<T> of(MapCodec<T> codec) {
        return new CriterionType<>(codec);
    }
}
