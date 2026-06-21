package red.jackf.whereisit.api.events;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Drop-in replacement for Fabric's {@code net.fabricmc.fabric.api.event.Event}, used by Where Is It's plugin system.
 *
 * <p>Supports phase-ordered listeners exactly like Fabric's Event: phases declared via
 * {@link #createWithPhases(Class, Function, ResourceLocation...)} define execution order, and listeners registered to
 * an undeclared phase are appended after the declared ones. {@link #register(Object)} registers to
 * {@link #DEFAULT_PHASE}.</p>
 */
public final class SimpleEvent<T> {
    /** The default phase, equivalent to Fabric's {@code Event.DEFAULT_PHASE}. */
    public static final ResourceLocation DEFAULT_PHASE = ResourceLocation.tryParse("default");

    private final Class<T> type;
    private final Function<T[], T> invokerFactory;
    private final List<ResourceLocation> phaseOrder = new ArrayList<>();
    private final Map<ResourceLocation, List<T>> handlersByPhase = new LinkedHashMap<>();
    private T invoker;

    private SimpleEvent(Class<T> type, Function<T[], T> invokerFactory, ResourceLocation[] phases) {
        this.type = type;
        this.invokerFactory = invokerFactory;
        for (ResourceLocation phase : phases) {
            if (!this.phaseOrder.contains(phase)) this.phaseOrder.add(phase);
        }
        if (!this.phaseOrder.contains(DEFAULT_PHASE)) this.phaseOrder.add(DEFAULT_PHASE);
        update();
    }

    public static <T> SimpleEvent<T> createArrayBacked(Class<T> type, Function<T[], T> invokerFactory) {
        return new SimpleEvent<>(type, invokerFactory, new ResourceLocation[]{DEFAULT_PHASE});
    }

    public static <T> SimpleEvent<T> createWithPhases(Class<T> type, Function<T[], T> invokerFactory, ResourceLocation... phases) {
        return new SimpleEvent<>(type, invokerFactory, phases);
    }

    /** Register a listener to the {@link #DEFAULT_PHASE default phase}. */
    public void register(T listener) {
        register(DEFAULT_PHASE, listener);
    }

    /** Register a listener to a specific phase. Listeners within a phase run in registration order. */
    public void register(ResourceLocation phase, T listener) {
        if (!phaseOrder.contains(phase)) phaseOrder.add(phase);
        handlersByPhase.computeIfAbsent(phase, p -> new ArrayList<>()).add(listener);
        update();
    }

    private void update() {
        List<T> all = new ArrayList<>();
        for (ResourceLocation phase : phaseOrder) {
            List<T> list = handlersByPhase.get(phase);
            if (list != null) all.addAll(list);
        }
        @SuppressWarnings("unchecked")
        T[] array = all.toArray((T[]) java.lang.reflect.Array.newInstance(type, 0));
        invoker = invokerFactory.apply(array);
    }

    public T invoker() {
        return invoker;
    }
}
