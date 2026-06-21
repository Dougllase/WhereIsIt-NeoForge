package red.jackf.whereisit.api.criteria.builtin;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidUtil;
import red.jackf.whereisit.api.criteria.Criterion;
import red.jackf.whereisit.api.criteria.CriterionType;

import java.util.Optional;

/**
 * Matches against a specific fluid, by targeting buckets, bottles and similar fluid containers. Uses NeoForge's
 * {@link FluidUtil} fluid-capability lookup (replacing the original Fabric Transfer API usage).
 */
public record FluidCriterion(Fluid fluid) implements Criterion {
    public static final MapCodec<FluidCriterion> CODEC = BuiltInRegistries.FLUID.byNameCodec().fieldOf("fluid").xmap(FluidCriterion::new, FluidCriterion::fluid);
    public static final CriterionType<FluidCriterion> TYPE = CriterionType.of(CODEC);

    @Override
    public CriterionType<?> type() {
        return TYPE;
    }

    @Override
    public boolean valid() {
        return this.fluid != Fluids.EMPTY;
    }

    @Override
    public boolean test(ItemStack stack) {
        Optional<net.neoforged.neoforge.fluids.FluidStack> contained = FluidUtil.getFluidContained(stack);
        if (contained.isEmpty()) return false;
        return contained.get().getFluid() == this.fluid;
    }
}
