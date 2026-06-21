package red.jackf.whereisit.api;

import red.jackf.whereisit.api.events.SimpleEvent;
import net.minecraft.resources.ResourceLocation;
import red.jackf.whereisit.WhereIsIt;

public interface EventPhases {
    ResourceLocation PRIORITY = WhereIsIt.id("priority");
    ResourceLocation DEFAULT = SimpleEvent.DEFAULT_PHASE;
    ResourceLocation FALLBACK = WhereIsIt.id("fallback");
}
