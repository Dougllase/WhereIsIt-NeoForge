package red.jackf.whereisit.client.api.events;

import red.jackf.whereisit.api.events.SimpleEvent;

public interface OnResultsCleared {
    SimpleEvent<OnResultsCleared> EVENT = SimpleEvent.createArrayBacked(OnResultsCleared.class, invokers -> () -> {
        for (OnResultsCleared invoker : invokers) {
            invoker.onResultsCleared();
        }
    });

    void onResultsCleared();
}
