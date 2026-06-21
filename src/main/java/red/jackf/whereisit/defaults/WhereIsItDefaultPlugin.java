package red.jackf.whereisit.defaults;

import red.jackf.whereisit.api.WhereIsItPlugin;

public class WhereIsItDefaultPlugin implements WhereIsItPlugin {
    @Override
    public void load() {
        BuiltInCriteria.setup();
        DefaultConnectedBlocksGrabbers.setup();
        DefaultNestedItemsGrabbers.setup();

        DefaultNestedItemStackSearchers.setup();
    }
}
