package red.jackf.whereisit.client.api;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Set;

/**
 * Utilities for working with Where Is It's rendering.
 *
 * <p>Stub implementation for the refactored architecture. Rendering is now handled
 * by the new render pipeline; these methods are kept for API compatibility.</p>
 */
@SuppressWarnings("unused")
public interface RenderUtils {
    static Set<BlockPos> getCurrentlyRendered() {
        return Collections.emptySet();
    }

    static Set<BlockPos> getCurrentlyRenderedWithNames() {
        return Collections.emptySet();
    }

    static void scheduleLabelRender(Vec3 pos, Component name) {
        // No-op in refactored architecture.
    }

    static void scheduleLabelRender(Vec3 pos, Component name, boolean seeThrough) {
        // No-op in refactored architecture.
    }
}
