package red.jackf.whereisit.defaults;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import red.jackf.whereisit.api.search.NestedItemStackSearcher;

public class DefaultNestedItemStackSearchers {
    @SuppressWarnings("removal")
    static void setup() {
        setupShulkerBoxes();
        setupBundles();
    }

    @SuppressWarnings("removal")
    private static void setupShulkerBoxes() {
        NestedItemStackSearcher.EVENT.register((source, predicate) -> {
            ItemContainerContents contents = source.get(DataComponents.CONTAINER);

            if (contents != null) {
                return contents.stream().anyMatch(predicate);
            } else {
                return false;
            }
        });
    }

    @SuppressWarnings("removal")
    private static void setupBundles() {
        NestedItemStackSearcher.EVENT.register((source, predicate) -> {
            BundleContents contents = source.get(DataComponents.BUNDLE_CONTENTS);

            if (contents != null) {
                return contents.itemCopyStream().anyMatch(predicate);
            }
            return false;
        });
    }
}
