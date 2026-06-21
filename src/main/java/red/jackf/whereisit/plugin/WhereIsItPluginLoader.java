package red.jackf.whereisit.plugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.defaults.WhereIsItDefaultPlugin;

/**
 * Loads the built-in default plugin. (Fabric entrypoint discovery was removed in the NeoForge port; only the bundled
 * default plugin is wired up here.)
 */
public class WhereIsItPluginLoader {
    private static final Logger LOGGER = LogManager.getLogger(WhereIsIt.class.getCanonicalName() + "/Plugins");

    public static void load() {
        LOGGER.debug("Loading default common plugin");
        new WhereIsItDefaultPlugin().load();
    }
}
