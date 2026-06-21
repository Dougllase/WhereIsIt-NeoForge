package red.jackf.whereisit.api.criteria.builtin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import red.jackf.whereisit.api.criteria.Criterion;
import red.jackf.whereisit.api.criteria.CriterionType;

import java.util.Locale;
import java.util.Optional;

/**
 * Matches against the custom name of an ItemStack. If null, checks for lack of name. Looks for the whole name.
 */
public record NameCriterion(@Nullable String name) implements Criterion {
    // Normalize at construction so all code paths (Codec deserialization,
    // direct `new NameCriterion(...)`, etc.) produce a canonical lowercase
    // form. Without this, e.g. DefaultStackToCriteriaConverters constructs
    // a NameCriterion from the raw display name, causing mismatches against
    // names that were lowercased during codec deserialization.
    public NameCriterion {
        if (name != null) {
            name = name.toLowerCase(Locale.ROOT);
        }
    }

    public static final MapCodec<NameCriterion> CODEC = Codec.STRING.optionalFieldOf("name")
            .xmap(opt -> new NameCriterion(opt.orElse(null)),
                  n -> Optional.ofNullable(n.name))
            .fieldOf("name");
    public static final CriterionType<NameCriterion> TYPE = CriterionType.of(CODEC);

    @Override
    public CriterionType<?> type() {
        return TYPE;
    }

    @Override
    public boolean test(ItemStack stack) {
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        if (customName == null) {
            return name == null;
        } else if (this.name == null) {
            return false;
        } else {
            return customName.getString().toLowerCase(Locale.ROOT).contains(this.name);
        }
    }
}
