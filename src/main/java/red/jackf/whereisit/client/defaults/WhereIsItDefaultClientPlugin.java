package red.jackf.whereisit.client.defaults;

import red.jackf.whereisit.client.api.WhereIsItClientPlugin;

public class WhereIsItDefaultClientPlugin implements WhereIsItClientPlugin {
    public void load() {
        SearchRequestPopulatorDefaults.setup();
        OverlayStackBehaviorDefaults.setup();
        SearchInvokerDefaults.setup();
        ShouldIgnoreKeyDefaults.setup();
    }
}
