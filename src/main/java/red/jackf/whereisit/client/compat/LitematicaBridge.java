package red.jackf.whereisit.client.compat;

import net.minecraft.world.item.ItemStack;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.client.WhereIsItClient;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridge to access Litematica/Forgematica API via reflection.
 * This allows WhereIsIt to integrate with Litematica when present,
 * without requiring it as a compile-time dependency.
 *
 * <p>NeoForge Forgematica package: {@code fi.dy.masa.litematica}</p>
 */
public class LitematicaBridge {
    private static Boolean loaded = null;
    private static Method getDataManagerMethod;
    private static Method getSchematicPlacementManagerMethod;
    private static Method getSelectedSchematicPlacementMethod;
    private static Method getMaterialListMethod;
    private static Method getMaterialsAllMethod;
    private static Method getStackMethod;
    private static Method getCountTotalMethod;
    private static Method getCountMissingMethod;
    private static Method getCountAvailableMethod;
    private static Method getNameMethod;
    private static boolean methodsResolved = false;

    /** Check if Litematica/Forgematica is loaded. */
    public static boolean isLitematicaLoaded() {
        if (loaded == null) {
            try {
                Class.forName("fi.dy.masa.litematica.Litematica");
                loaded = true;
            } catch (ClassNotFoundException e) {
                loaded = false;
            }
        }
        return loaded;
    }

    /** Resolve and cache reflection methods. */
    private static synchronized void resolveMethods() {
        if (methodsResolved) return;
        methodsResolved = true;
        if (!isLitematicaLoaded()) return;

        try {
            Class<?> dataManagerClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
            getDataManagerMethod = dataManagerClass.getMethod("getInstance");
            getSchematicPlacementManagerMethod = dataManagerClass.getMethod("getSchematicPlacementManager");

            Class<?> placementManagerClass = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager");
            getSelectedSchematicPlacementMethod = placementManagerClass.getMethod("getSelectedSchematicPlacement");

            getMaterialListMethod = dataManagerClass.getMethod("getMaterialList");

            Class<?> materialListBaseClass = Class.forName("fi.dy.masa.litematica.materials.MaterialListBase");
            getMaterialsAllMethod = materialListBaseClass.getMethod("getMaterialsAll");

            Class<?> materialListEntryClass = Class.forName("fi.dy.masa.litematica.materials.MaterialListEntry");
            getStackMethod = materialListEntryClass.getMethod("getStack");
            getCountTotalMethod = materialListEntryClass.getMethod("getCountTotal");
            getCountMissingMethod = materialListEntryClass.getMethod("getCountMissing");
            getCountAvailableMethod = materialListEntryClass.getMethod("getCountAvailable");

            Class<?> placementClass = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
            getNameMethod = placementClass.getMethod("getName");
        } catch (Exception e) {
            WhereIsItClient.LOGGER.warn("Failed to resolve Litematica API methods", e);
        }
    }

    /** Data record for a material list entry from Litematica. */
    public record SchematicMaterialEntry(ItemStack stack, int countTotal, int countMissing, int countAvailable) {}

    /** Get the name of the currently selected schematic placement, or null. */
    public static String getSelectedPlacementName() {
        resolveMethods();
        if (!isLitematicaLoaded() || getSelectedSchematicPlacementMethod == null) return null;
        try {
            Object placementManager = getSchematicPlacementManagerMethod.invoke(null);
            Object placement = getSelectedSchematicPlacementMethod.invoke(placementManager);
            if (placement == null) return null;
            return (String) getNameMethod.invoke(placement);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the material list entries for the currently selected placement.
     * Returns an empty list if Litematica is not loaded, no placement is selected,
     * or no material list is available.
     */
    public static List<SchematicMaterialEntry> getMaterialListEntries() {
        resolveMethods();
        if (!isLitematicaLoaded() || getMaterialListMethod == null) return List.of();

        try {
            // Try to get existing material list from DataManager
            Object materialList = getMaterialListMethod.invoke(null);
            if (materialList == null) return List.of();

            @SuppressWarnings("unchecked")
            List<?> entries = (List<?>) getMaterialsAllMethod.invoke(materialList);

            List<SchematicMaterialEntry> result = new ArrayList<>();
            for (Object entry : entries) {
                ItemStack stack = (ItemStack) getStackMethod.invoke(entry);
                int total = (int) getCountTotalMethod.invoke(entry);
                int missing = (int) getCountMissingMethod.invoke(entry);
                int available = (int) getCountAvailableMethod.invoke(entry);
                result.add(new SchematicMaterialEntry(stack, total, missing, available));
            }
            return result;
        } catch (Exception e) {
            WhereIsItClient.LOGGER.warn("Failed to get Litematica material list", e);
            return List.of();
        }
    }
}
