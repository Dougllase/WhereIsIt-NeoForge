package red.jackf.whereisit;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import red.jackf.whereisit.config.WhereIsItConfig;
import red.jackf.whereisit.plugin.WhereIsItPluginLoader;

@Mod(WhereIsIt.MODID)
public class WhereIsIt {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final String MODID = "whereisit";

    public WhereIsIt(IEventBus modEventBus) {
        try {
            WhereIsItConfig.INSTANCE.load();
            WhereIsItConfig.INSTANCE.instance().validate();
        } catch (Exception ex) {
            LOGGER.error("Error loading WhereIsIt config, restoring default", ex);
        }
        WhereIsItConfig.INSTANCE.save();
        LOGGER.debug("Setup Common");

        // Load built-in plugins: registers criteria, connected-block grabbers, nested-item grabbers, etc.
        WhereIsItPluginLoader.load();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
