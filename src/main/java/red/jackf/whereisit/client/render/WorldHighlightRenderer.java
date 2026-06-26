package red.jackf.whereisit.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.client.tracking.TrackerManager;
import red.jackf.whereisit.client.tracking.TrackerSnapshot;

/**
 * Renders coloured block overlays, connecting lines, and floating labels for tracked-item positions.
 */
@EventBusSubscriber(modid = WhereIsIt.MODID, value = Dist.CLIENT)
public final class WorldHighlightRenderer {
    private WorldHighlightRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (!TrackerManager.isTracking()) return;

        TrackerSnapshot snapshot = TrackerManager.getSnapshot();
        if (snapshot.isEmpty()) return;

        RenderState state = RenderState.fromSnapshot(snapshot);
        if (state.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        Matrix4f modelView = event.getModelViewMatrix();
        Matrix4f projection = event.getProjectionMatrix();

        // ---- Block overlays (translucent filled boxes) ------------------------
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder overlayBuilder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        BufferBuilder lineBuilder = tesselator.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);

        for (RenderState.PerItem group : state.groups()) {
            int color = group.color();
            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f;
            float faceAlpha = 0.18f;
            float edgeAlpha = 0.55f;
            float lineAlpha = 0.4f;

            for (RenderState.Highlight hl : group.highlights()) {
                BlockPos pos = hl.pos();
                double bx = pos.getX() - cam.x;
                double by = pos.getY() - cam.y;
                double bz = pos.getZ() - cam.z;
                float x0 = (float) bx, y0 = (float) by, z0 = (float) bz;
                float x1 = x0 + 1f, y1 = y0 + 1f, z1 = z0 + 1f;

                // --- Filled box faces ---
                addFilledBoxVertices(overlayBuilder, x0, y0, z0, x1, y1, z1, r, g, b, faceAlpha);

                // --- Wireframe edges ---
                addFilledBoxVertices(lineBuilder, x0, y0, z0, x1, y1, z1, r, g, b, edgeAlpha);

                // --- Connecting lines (double chests etc.) ---
                for (BlockPos connected : hl.connected()) {
                    Vec3 start = Vec3.atCenterOf(pos).subtract(cam);
                    Vec3 end = Vec3.atCenterOf(connected).subtract(cam);
                    lineBuilder.addVertex((float) start.x, (float) start.y, (float) start.z).setColor(r, g, b, lineAlpha);
                    lineBuilder.addVertex((float) end.x, (float) end.y, (float) end.z).setColor(r, g, b, lineAlpha);
                }
            }
        }

        BufferUploader.drawWithShader(overlayBuilder.buildOrThrow());
        BufferUploader.drawWithShader(lineBuilder.buildOrThrow());

        // ---- Floating labels ---------------------------------------------------
        MultiBufferSource.BufferSource textSource = mc.renderBuffers().bufferSource();
        var font = mc.font;

        for (RenderState.PerItem group : state.groups()) {
            for (RenderState.Highlight hl : group.highlights()) {
                Component label = hl.label();
                if (label == null) continue;

                Vec3 offset = hl.labelOffset();
                Vec3 worldCenter = Vec3.atCenterOf(hl.pos());
                Vec3 pos = worldCenter.add(offset).subtract(cam);

                float scale = 0.025f;
                int bgColor = (group.color() & 0x00FFFFFF) | 0x40000000;

                poseStack.pushPose();
                poseStack.translate(pos.x, pos.y, pos.z);
                poseStack.mulPose(camera.rotation());
                poseStack.scale(-scale, -scale, scale);

                float halfW = font.width(label) / 2f;
                font.drawInBatch(
                        label,
                        -halfW, -4f,
                        0xFFFFFFFF,
                        false,
                        poseStack.last().pose(),
                        textSource,
                        net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
                        bgColor,
                        0x00F000F0
                );

                poseStack.popPose();
            }
        }

        textSource.endBatch();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    /**
     * 6-face coloured-box vertices into a QUADS-mode buffer.
     */
    private static void addFilledBoxVertices(BufferBuilder builder,
                                             float x0, float y0, float z0, float x1, float y1, float z1,
                                             float r, float g, float b, float a) {
        // -X face
        builder.addVertex(x0, y0, z0).setColor(r, g, b, a);
        builder.addVertex(x0, y1, z0).setColor(r, g, b, a);
        builder.addVertex(x0, y1, z1).setColor(r, g, b, a);
        builder.addVertex(x0, y0, z1).setColor(r, g, b, a);
        // +X face
        builder.addVertex(x1, y0, z1).setColor(r, g, b, a);
        builder.addVertex(x1, y1, z1).setColor(r, g, b, a);
        builder.addVertex(x1, y1, z0).setColor(r, g, b, a);
        builder.addVertex(x1, y0, z0).setColor(r, g, b, a);
        // -Y face
        builder.addVertex(x0, y0, z1).setColor(r, g, b, a);
        builder.addVertex(x1, y0, z1).setColor(r, g, b, a);
        builder.addVertex(x1, y0, z0).setColor(r, g, b, a);
        builder.addVertex(x0, y0, z0).setColor(r, g, b, a);
        // +Y face
        builder.addVertex(x0, y1, z0).setColor(r, g, b, a);
        builder.addVertex(x1, y1, z0).setColor(r, g, b, a);
        builder.addVertex(x1, y1, z1).setColor(r, g, b, a);
        builder.addVertex(x0, y1, z1).setColor(r, g, b, a);
        // -Z face
        builder.addVertex(x1, y0, z0).setColor(r, g, b, a);
        builder.addVertex(x1, y1, z0).setColor(r, g, b, a);
        builder.addVertex(x0, y1, z0).setColor(r, g, b, a);
        builder.addVertex(x0, y0, z0).setColor(r, g, b, a);
        // +Z face
        builder.addVertex(x0, y0, z1).setColor(r, g, b, a);
        builder.addVertex(x0, y1, z1).setColor(r, g, b, a);
        builder.addVertex(x1, y1, z1).setColor(r, g, b, a);
        builder.addVertex(x1, y0, z1).setColor(r, g, b, a);
    }
}
