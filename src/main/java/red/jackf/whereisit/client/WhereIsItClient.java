package red.jackf.whereisit.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.SearchResult;
import red.jackf.whereisit.client.api.events.*;
import red.jackf.whereisit.client.gui.ItemBrowserScreen;
import red.jackf.whereisit.client.plugin.WhereIsItClientPluginLoader;
import red.jackf.whereisit.client.render.CurrentGradientHolder;
import red.jackf.whereisit.client.render.Rendering;
import red.jackf.whereisit.client.tracking.ContainerTracker;
import red.jackf.whereisit.client.tracking.TrackingState;
import red.jackf.whereisit.client.util.TextUtil;
import red.jackf.whereisit.config.WhereIsItConfig;

import java.util.Collection;

@EventBusSubscriber(modid = WhereIsIt.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class WhereIsItClient {
    public static final Logger LOGGER = LogUtils.getLogger();

    // The key mapping object is created up-front; it is registered to Minecraft in the MOD-bus subscriber below.
    public static final KeyMapping SEARCH = new KeyMapping("key.whereisit.search", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, "key.categories.whereisit");

    // Opens the item browser (icon grid of recorded items within range); clicking an item starts tracking it.
    public static final KeyMapping OPEN_BROWSER = new KeyMapping("key.whereisit.openBrowser", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.whereisit");

    // only clear results after faded + this, so players can repeat the search by pressing the key
    public static final int POST_FADEOUT_REPEAT_PERIOD_TICKS = 20 * 20;

    private static boolean inGame = false;
    public static boolean closedScreenThisSearch = false;

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!inGame) return;
        if (!WhereIsItConfig.INSTANCE.instance().getClient().showSlotHighlights) return;
        Rendering.renderSlotHighlight(event.getScreen(), event.getGuiGraphics(),
                event.getMouseX(), event.getMouseY(),
                event.getPartialTick());
    }

    @SubscribeEvent
    public static void onScreenKey(ScreenEvent.KeyPressed.Post event) {
        if (!inGame) return;
        if (SEARCH.matches(event.getKeyCode(), event.getScanCode()) && !ShouldIgnoreKey.EVENT.invoker().shouldIgnoreKey()) {
            SearchRequest request = createRequest(Minecraft.getInstance(), event.getScreen());
            if (request.hasCriteria()) {
                SearchInvoker.doSearch(request);
            } else {
                Rendering.resetSearchTime();
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        // Track connection state via the loaded level (replaces ClientPlayerNetworkEvent, which moved packages in 21.1).
        boolean connected = Minecraft.getInstance().level != null;
        if (inGame && !connected) {
            clearResults();
            TrackingState.stopAll();
            ContainerTracker.onLeaveWorld();
        }
        if (!inGame && connected) {
            ContainerTracker.onJoinWorld();
        }
        inGame = connected;
        if (!connected) return;

        ContainerTracker.tickAutosave();

        // Tick container tracking: detects screen changes, records closed containers, and updates
        // open container contents in real-time (e.g. hopper transfers, other players modifying).
        ContainerTracker.tick();

        // Periodic cleanup scan: removes records for containers that no longer exist
        // (destroyed, replaced, moved by pistons, etc.)
        ContainerTracker.tickCleanupScan();

        // Tracking owns Rendering.results while active; bypass the fadeout timer + expiry cleanup.
        if (TrackingState.isTracking()) {
            TrackingState.tickRefresh();
        } else {
            Rendering.incrementTicksSinceSearch();
            if (Rendering.getTicksSinceSearch() > (WhereIsItConfig.INSTANCE.instance().getCommon().fadeoutTimeTicks + POST_FADEOUT_REPEAT_PERIOD_TICKS)) {
                clearResults();
            }
        }

        // Open the item browser; does NOT stop existing tracking (user can manage tracking from the browser).
        if (Minecraft.getInstance().screen == null && OPEN_BROWSER.consumeClick()) {
            Minecraft.getInstance().setScreen(new ItemBrowserScreen());
        }

        if (Minecraft.getInstance().screen == null && SEARCH.consumeClick()) {
            var player = Minecraft.getInstance().player;
            if (player == null) return;
            ItemStack item = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (item.isEmpty()) item = player.getItemInHand(InteractionHand.OFF_HAND);
            if (!item.isEmpty() && WhereIsItConfig.INSTANCE.instance().getClient().searchUsingItemInHand) {
                var request = new SearchRequest();
                SearchRequestPopulator.addItemStack(request, item, SearchRequestPopulator.Context.inventory());
                if (request.hasCriteria()) SearchInvoker.doSearch(request);
            } else {
                Rendering.resetSearchTime();
            }
        }
    }

    public static boolean doSearch(SearchRequest request) {
        Rendering.resetSearchTime();
        updateRendering(request);
        LOGGER.debug("Starting request: %s".formatted(request));

        if (WhereIsItConfig.INSTANCE.instance().getClient().debug.printSearchRequestsInChat && Minecraft.getInstance().player != null) {
            var text = TextUtil.prettyPrint(request.toTag());
            for (Component component : text)
                Minecraft.getInstance().player.sendSystemMessage(component);
        }

        var anySucceeded = SearchInvoker.EVENT.invoker().search(request, WhereIsItClient::receiveResults);

        if (anySucceeded && WhereIsItConfig.INSTANCE.instance().getClient().playSoundOnRequest) playRequestSound();

        return anySucceeded;
    }

    public static void receiveResults(Collection<SearchResult> results) {
        WhereIsItClient.LOGGER.debug("Search results: %s".formatted(results));
        if (WhereIsItConfig.INSTANCE.instance().getClient().closeGuiOnFoundResults && !closedScreenThisSearch) {
            closedScreenThisSearch = true;
            if (Minecraft.getInstance().screen != null && Minecraft.getInstance().player != null)
                Minecraft.getInstance().player.closeContainer();
        }
        OnResult.EVENT.invoker().onResults(results);
    }

    private static void clearResults() {
        OnResultsCleared.EVENT.invoker().onResultsCleared();
    }

    // clear previous state for rendering
    private static void updateRendering(SearchRequest request) {
        // Stop any active tracking before starting a one-shot search
        if (TrackingState.isTracking()) {
            TrackingState.stopAll();
        }
        clearResults();
        Rendering.setLastRequest(request);
        closedScreenThisSearch = false;

        CurrentGradientHolder.refreshColourScheme();
    }

    private static SearchRequest createRequest(Minecraft client, Screen screen1) {
        int mouseX = (int) (client.mouseHandler.xpos() * (double) client.getWindow()
                .getGuiScaledWidth() / (double) client.getWindow().getScreenWidth());
        int mouseY = (int) (client.mouseHandler.ypos() * (double) client.getWindow()
                .getGuiScaledHeight() / (double) client.getWindow().getScreenHeight());
        var request = new SearchRequest();
        SearchRequestPopulator.EVENT.invoker().grabStack(request, screen1, mouseX, mouseY);
        return request;
    }

    private static void playRequestSound() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 2f, 0.5f));
    }

    /**
     * MOD-bus subscriptions (key registration + client setup). Kept in a separate nested subscriber because a single
     * {@code @EventBusSubscriber} can only target one bus.
     */
    @EventBusSubscriber(modid = WhereIsIt.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    private static class ModEvents {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(SEARCH);
            event.register(OPEN_BROWSER);
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                CurrentGradientHolder.refreshColourScheme();
                WhereIsItClientPluginLoader.load();
                Rendering.setup();
            });
        }
    }
}
