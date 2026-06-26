package red.jackf.whereisit.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.client.tracking.TrackerManager;
import red.jackf.whereisit.client.tracking.TrackerSnapshot;
import red.jackf.whereisit.config.WhereIsItConfig;

import java.util.HashSet;
import java.util.Set;

/**
 * Draws coloured overlays on container-slots that contain tracked items.
 */
@EventBusSubscriber(modid = WhereIsIt.MODID, value = Dist.CLIENT)
public final class SlotHighlightRenderer {
    private SlotHighlightRenderer() {}

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!WhereIsItConfig.INSTANCE.instance().getClient().showSlotHighlights) return;
        if (!TrackerManager.isTracking()) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;

        TrackerSnapshot snapshot = TrackerManager.getSnapshot();
        if (snapshot.isEmpty()) return;

        // Collect tracked items + colors
        Set<Item> tracked = new HashSet<>(snapshot.trackedItems());

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        var buf = Tesselator.getInstance();
        var builder = buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        var pose = event.getGuiGraphics().pose();
        var matrix = pose.last().pose();

        int guiLeft = screen.getGuiLeft();
        int guiTop = screen.getGuiTop();

        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (!tracked.contains(stack.getItem())) continue;

            int x = guiLeft + slot.x;
            int y = guiTop + slot.y;
            int color = TrackerManager.trackedColor(stack.getItem());

            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f;
            float a = 0.35f;

            // Draw 2 px border
            // top
            builder.addVertex(matrix, x, y, 0).setColor(r, g, b, a);
            builder.addVertex(matrix, x + 16, y, 0).setColor(r, g, b, a);
            builder.addVertex(matrix, x + 16, y + 2, 0).setColor(r, g, b, a);
            builder.addVertex(matrix, x, y + 2, 0).setColor(r, g, b, a);
            // bottom
            builder.addVertex(matrix, x, y + 14, 0).setColor(r, g, b, a);
            builder.addVertex(matrix, x + 16, y + 14, 0).setColor(r, g, b, a);
            builder.addVertex(matrix, x + 16, y + 16, 0).setColor(r, g, b, a);
            builder.addVertex(matrix, x, y + 16, 0).setColor(r, g, b, a);
            // left
            builder.addVertex(matrix, x, y, 0).setColor(r, g, b, a);
            builder.addVertex(matrix, x + 2, y, 0).setColor(r, g, b, a);
            builder.addVertex(matrix, x + 2, y + 16, 0).setColor(r, g, b, a);
            builder.addVertex(matrix, x, y + 16, 0).setColor(r, g, b, a);
            // right
            builder.addVertex(matrix, x + 14, y, 0).setColor(r, g, b, a);
            builder.addVertex(matrix, x + 16, y, 0).setColor(r, g, b, a);
            builder.addVertex(matrix, x + 16, y + 16, 0).setColor(r, g, b, a);
            builder.addVertex(matrix, x + 14, y + 16, 0).setColor(r, g, b, a);

            // Fill overlay
            float fa = 0.12f;
            builder.addVertex(matrix, x + 2, y + 2, 0).setColor(r, g, b, fa);
            builder.addVertex(matrix, x + 14, y + 2, 0).setColor(r, g, b, fa);
            builder.addVertex(matrix, x + 14, y + 14, 0).setColor(r, g, b, fa);
            builder.addVertex(matrix, x + 2, y + 14, 0).setColor(r, g, b, fa);
        }

        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
}
