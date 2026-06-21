package red.jackf.whereisit.client.plugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.client.defaults.WhereIsItDefaultClientPlugin;

/**
 * Loads the built-in default client plugin. (Fabric entrypoint + object-share discovery were removed in the NeoForge
 * port; only the bundled default client plugin is wired up here.)
 */
public class WhereIsItClientPluginLoader {
    private static final Logger LOGGER = LogManager.getLogger(WhereIsIt.class.getCanonicalName() + "/Plugins/Client");

    public static void load() {
        LOGGER.debug("Loading default client plugin");
        new WhereIsItDefaultClientPlugin().load();
    }
}
