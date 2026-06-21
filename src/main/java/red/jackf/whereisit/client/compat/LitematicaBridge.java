package red.jackf.whereisit.client.compat;

import net.minecraft.world.item.ItemStack;
import red.jackf.whereisit.client.WhereIsItClient;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridge to access Litematica/Forgematica API via reflection.
 * This allows WhereIsIt to integrate with Litematica when present,
 * without requiring it as a compile-time dependency.
 *
 * <p>NeoForge Forgematica package: {@code fi.dy.masa.litematica}</p>
 *
 * <p>All loading uses {@link Class#forName(String, boolean, ClassLoader)} with
 * {@code initialize=false} so we never trigger Litematica's static initializers
 * just to ask whether it's present. Method resolution distinguishes static vs
 * instance methods so the bridge keeps working if upstream Litematica makes
 * accessors instance-bound (or vice versa) between versions.</p>
 */
public class LitematicaBridge {
    private static Boolean loaded = null;
    private static Method getDataManagerInstanceMethod; // static DataManager.getInstance()
    private static Method getSchematicPlacementManagerMethod; // may be static or instance
    private static Method getSelectedSchematicPlacementMethod; // instance on SchematicPlacementManager
    private static Method getMaterialListMethod; // may be static or instance on DataManager
    private static Method getMaterialsAllMethod; // instance on MaterialListBase
    private static Method getStackMethod;
    private static Method getCountTotalMethod;
    private static Method getCountMissingMethod;
    private static Method getCountAvailableMethod;
    private static Method getNameMethod;
    private static boolean methodsResolved = false;

    /** Check if Litematica/Forgematica is loaded, without triggering its <clinit>. */
    public static boolean isLitematicaLoaded() {
        if (loaded == null) {
            try {
                Class.forName("fi.dy.masa.litematica.Litematica", false, LitematicaBridge.class.getClassLoader());
                loaded = true;
            } catch (ClassNotFoundException e) {
                loaded = false;
            } catch (Throwable t) {
                // Defensive: e.g. NoClassDefFoundError from a broken/partial install.
                WhereIsItClient.LOGGER.debug("Litematica detection raised unexpected error; treating as absent", t);
                loaded = false;
            }
        }
        return loaded;
    }

    /** Resolve and cache reflection methods. Idempotent and tolerant of partial failure. */
    private static synchronized void resolveMethods() {
        if (methodsResolved) return;
        methodsResolved = true;
        if (!isLitematicaLoaded()) return;

        try {
            Class<?> dataManagerClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
            getDataManagerInstanceMethod = dataManagerClass.getMethod("getInstance");
            getSchematicPlacementManagerMethod = dataManagerClass.getMethod("getSchematicPlacementManager");
            getMaterialListMethod = dataManagerClass.getMethod("getMaterialList");

            Class<?> placementManagerClass = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager");
            getSelectedSchematicPlacementMethod = placementManagerClass.getMethod("getSelectedSchematicPlacement");

            Class<?> materialListBaseClass = Class.forName("fi.dy.masa.litematica.materials.MaterialListBase");
            getMaterialsAllMethod = materialListBaseClass.getMethod("getMaterialsAll");

            Class<?> materialListEntryClass = Class.forName("fi.dy.masa.litematica.materials.MaterialListEntry");
            getStackMethod = materialListEntryClass.getMethod("getStack");
            getCountTotalMethod = materialListEntryClass.getMethod("getCountTotal");
            getCountMissingMethod = materialListEntryClass.getMethod("getCountMissing");
            getCountAvailableMethod = materialListEntryClass.getMethod("getCountAvailable");

            Class<?> placementClass = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
            getNameMethod = placementClass.getMethod("getName");
        } catch (Throwable t) {
            WhereIsItClient.LOGGER.warn("Failed to resolve Litematica API methods; bridge will be inactive", t);
        }
    }

    /**
     * Invoke a method that may be either static or instance-bound on DataManager.
     * Resolves the actual receiver lazily so a single API surface works across
     * Litematica versions that have flipped between static and instance accessors.
     */
    private static Object invokeOnDataManager(Method method) throws ReflectiveOperationException {
        if (method == null) return null;
        Object receiver = null;
        if (!Modifier.isStatic(method.getModifiers())) {
            if (getDataManagerInstanceMethod == null) return null;
            receiver = getDataManagerInstanceMethod.invoke(null);
            if (receiver == null) return null;
        }
        return method.invoke(receiver);
    }

    /** Data record for a material list entry from Litematica. */
    public record SchematicMaterialEntry(ItemStack stack, int countTotal, int countMissing, int countAvailable) {}

    /** Get the name of the currently selected schematic placement, or null. */
    public static String getSelectedPlacementName() {
        resolveMethods();
        if (!isLitematicaLoaded() || getSelectedSchematicPlacementMethod == null) return null;
        try {
            Object placementManager = invokeOnDataManager(getSchematicPlacementManagerMethod);
            if (placementManager == null) return null;
            Object placement = getSelectedSchematicPlacementMethod.invoke(placementManager);
            if (placement == null) return null;
            return (String) getNameMethod.invoke(placement);
        } catch (Throwable t) {
            // Don't spam on every frame; use debug since the GUI polls this.
            WhereIsItClient.LOGGER.debug("Failed to read selected Litematica placement name", t);
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
            Object materialList = invokeOnDataManager(getMaterialListMethod);
            if (materialList == null) return List.of();

            Object rawEntries = getMaterialsAllMethod.invoke(materialList);
            if (!(rawEntries instanceof List<?> entries)) return List.of();

            List<SchematicMaterialEntry> result = new ArrayList<>(entries.size());
            for (Object entry : entries) {
                if (entry == null) continue;
                try {
                    ItemStack stack = (ItemStack) getStackMethod.invoke(entry);
                    int total = (int) getCountTotalMethod.invoke(entry);
                    int missing = (int) getCountMissingMethod.invoke(entry);
                    int available = (int) getCountAvailableMethod.invoke(entry);
                    if (stack != null) {
                        result.add(new SchematicMaterialEntry(stack, total, missing, available));
                    }
                } catch (Throwable perEntry) {
                    // Skip a malformed entry rather than aborting the entire list.
                    WhereIsItClient.LOGGER.debug("Skipping malformed Litematica material entry", perEntry);
                }
            }
            return result;
        } catch (Throwable t) {
            WhereIsItClient.LOGGER.warn("Failed to get Litematica material list", t);
            return List.of();
        }
    }
}
