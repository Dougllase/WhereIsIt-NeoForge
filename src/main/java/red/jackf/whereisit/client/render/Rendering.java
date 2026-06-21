package red.jackf.whereisit.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import red.jackf.whereisit.WhereIsIt;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.SearchResult;
import red.jackf.whereisit.config.WhereIsItConfig;

import java.util.*;

@SuppressWarnings("resource") // i really don't want to call ClientLevel#close() thanks
@EventBusSubscriber(modid = WhereIsIt.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class Rendering {
    /** A search result associated with a tracked item, used for multi-colour rendering. */
    public record TrackedResult(SearchResult result, Item trackedItem) {}

    private static final Map<BlockPos, SearchResult> results = new HashMap<>();
    private static final Map<BlockPos, SearchResult> namedResults = new HashMap<>();

    // Multi-item tracking results with item association for per-item colouring
    private static List<TrackedResult> trackedResults = List.of();

    private static final List<ScheduledLabel> scheduledLabels = new ArrayList<>();

    private record ScheduledLabel(Vec3 position, Component text, boolean seeThrough) {}

    private static long ticksSinceSearch = 0;
    // When true (tracking mode), highlights persist and ignore the fadeout timer; results are owned by TrackingState.
    private static boolean trackingActive = false;
    @Nullable
    private static SearchRequest lastRequest = null;

    public static void setup() {
        // No-op: world rendering is now driven by onRenderLevelStage via @EventBusSubscriber.
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        var camera = event.getCamera();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);

        // schedule highlight label renders
        if (shouldBeRendering() && WhereIsItConfig.INSTANCE.instance().getClient().showContainerNamesInResults) {
            for (SearchResult value : namedResults.values())
                scheduleLabel(value.pos().getCenter().add(value.nameOffset()), value.name(), WhereIsItConfig.INSTANCE.instance().getCommon().debug.labelsAreSeeThrough);
        }

        // render scheduled labels
        if (!scheduledLabels.isEmpty()) {
            renderLabels(camera, event.getPoseStack(), Minecraft.getInstance().renderBuffers().bufferSource());
            scheduledLabels.clear();
        }

        // render boxes
        if (shouldBeRendering() && !results.isEmpty()) {
            renderBoxes(camera, event.getPoseStack(), partialTick, getRenderingProgress(partialTick));
        }
    }

    private static float getRenderingProgress(float tickDelta) {
        return Mth.clamp((getTicksSinceSearch() + tickDelta) / WhereIsItConfig.INSTANCE.instance().getCommon().fadeoutTimeTicks, 0f, 1f);
    }

    private static boolean shouldBeRendering() {
        return trackingActive || getTicksSinceSearch() <= WhereIsItConfig.INSTANCE.instance().getCommon().fadeoutTimeTicks;
    }

    // smooth scaling for the cube highlights
    private static float easingFunc(float progress) {
        var power = 32f;
        return (float) ((1 - Math.pow(progress, power)) * (1 - Math.pow(1 - progress, power)) * (1 - (progress / 4f)));
    }

    public static void addResults(Collection<SearchResult> newResults) {
        for (SearchResult result : newResults) {
            // TODO: when names are added, prioritise the one with a name
            results.put(result.pos(), result);
            if (result.name() != null) namedResults.put(result.pos(), result);
        }
    }

    /** Replace all results at once (used by tracking mode, which refreshes the full set each scan). */
    public static void setResults(Collection<SearchResult> newResults) {
        results.clear();
        namedResults.clear();
        trackedResults = List.of();
        for (SearchResult result : newResults) {
            results.put(result.pos(), result);
            if (result.name() != null) namedResults.put(result.pos(), result);
        }
    }

    /** Set tracked results with item association for multi-colour rendering. */
    public static void setTrackedResults(List<TrackedResult> newTrackedResults) {
        trackedResults = newTrackedResults;
        // Also populate the legacy results map for label rendering etc.
        results.clear();
        namedResults.clear();
        for (TrackedResult tr : newTrackedResults) {
            results.put(tr.result().pos(), tr.result());
            if (tr.result().name() != null) namedResults.put(tr.result().pos(), tr.result());
        }
    }

    public static boolean isTrackingActive() {
        return trackingActive;
    }

    public static void setTrackingActive(boolean active) {
        trackingActive = active;
    }

    public static void setLastRequest(@Nullable SearchRequest request) {
        lastRequest = request;
    }

    public static void clearResults() {
        lastRequest = null;
        results.clear();
        namedResults.clear();
        trackedResults = List.of();
    }

    public static Map<BlockPos, SearchResult> getResults() {
        return results;
    }

    public static Map<BlockPos, SearchResult> getNamedResults() {
        return namedResults;
    }

    private static float getBaseProgress(long ticks, float delta) {
        float base = ticks + delta;
        base *= WhereIsItConfig.INSTANCE.instance().getClient().highlightTimeFactor;
        return (base % 80) / 80;
    }

    //////////////////////
    // SCREEN RENDERING //
    //////////////////////

    // render a highlight behind an item
    public static void renderSlotHighlight(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float tickDelta) {
        if (!shouldBeRendering()) return;

        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            var time = getBaseProgress(getTicksSinceSearch(), tickDelta);

            // Check if we're in multi-item tracking mode
            if (trackingActive && !trackedResults.isEmpty()) {
                // Multi-item tracking: colour each slot by its tracked item.
                //
                // Previously this iterated `trackedResults` (one entry per matched container, often dozens),
                // calling SearchRequest.check() once per (slot, container) pair plus its nested-search cost.
                // With N slots and M matched containers, that is O(N*M*nestedSearch) every frame.
                //
                // Each (request, colour) pair is identical for every container with the same tracked item, so
                // we only need to check each tracked Item once per slot. Snapshot the (item -> request/colour)
                // table to the local set of currently tracked items (bounded by maxTrackedItems, typically <=8).
                java.util.List<net.minecraft.world.item.Item> trackedItems =
                        red.jackf.whereisit.client.tracking.TrackingState.getTrackedItems();
                if (!trackedItems.isEmpty()) {
                    float alphaBase = 0.5f + 0.5f * Mth.sin((getTicksSinceSearch() + tickDelta) * 0.1f);
                    int a = (int) (alphaBase * 255);
                    for (Slot slot : containerScreen.getMenu().slots) {
                        if (!slot.isActive() || !slot.hasItem()) continue;
                        ItemStack slotStack = slot.getItem();
                        for (net.minecraft.world.item.Item trackedItem : trackedItems) {
                            SearchRequest req = red.jackf.whereisit.client.tracking.TrackingState.getTrackedRequest(trackedItem);
                            if (req == null) continue;
                            if (SearchRequest.check(slotStack, req)) {
                                int color = red.jackf.whereisit.client.tracking.TrackingState.getTrackedColor(trackedItem);
                                int highlightColor = (a << 24) | (color & 0x00FFFFFF);
                                var x = slot.x + containerScreen.leftPos;
                                var y = slot.y + containerScreen.topPos;
                                graphics.fill(x, y, x + 16, y + 16, highlightColor);
                                break;
                            }
                        }
                    }
                }
            } else {
                // Original single-request gradient mode
                for (Slot slot : containerScreen.getMenu().slots) {
                    if (slot.isActive() && slot.hasItem() && lastRequest != null &&
                            SearchRequest.check(slot.getItem(), lastRequest)) {
                        var x = slot.x + containerScreen.leftPos;
                        var y = slot.y + containerScreen.topPos;
                        var progress = time;
                        progress += (slot.x / 256f) * WhereIsItConfig.INSTANCE.instance().getClient().slotHighlightXFactor;
                        progress -= ((mouseX + mouseY) / 1280f) * WhereIsItConfig.INSTANCE.instance().getClient().slotHighlightMouseFactor;
                        var colour = CurrentGradientHolder.getColour(progress);
                        graphics.fill(x, y, x + 16, y + 16, colour);
                    }
                }
            }
        }
    }

    ////////////////////
    // TEXT RENDERING //
    ////////////////////

    // schedule a label to be rendered; should be called before BEFORE_BLOCK_OUTLINE every frame
    public static void scheduleLabel(Vec3 pos, Component name, boolean seeThrough) {
        if (pos == null || name == null) return;
        scheduledLabels.add(new ScheduledLabel(pos, name, seeThrough));
    }

    // does the rendering of labels at BEFORE_BLOCK_OUTLINE
    @SuppressWarnings("DataFlowIssue")
    private static void renderLabels(Camera camera, PoseStack pose, MultiBufferSource consumers) {
        scheduledLabels.stream().sorted(Comparator.comparingDouble(label ->
                // sort by furthest to camera inwards
                -camera.rotation().transformInverse(label.position.toVector3f()).z
        )).forEach(label ->
                renderLabel(
                        label,
                        pose,
                        camera,
                        consumers));
    }

    // render an individual label
    private static void renderLabel(ScheduledLabel label, PoseStack pose, Camera camera, MultiBufferSource consumers) {
        pose.pushPose();

        Vec3 pos = label.position.subtract(camera.getPosition());

        pose.translate(pos.x, pos.y, pos.z);
        pose.mulPose(camera.rotation());
        var factor = 0.025f * WhereIsItConfig.INSTANCE.instance().getClient().containerNameLabelScale;
        pose.scale(factor, -factor, factor);
        var matrix4f = pose.last().pose();
        var width = Minecraft.getInstance().font.width(label.text);
        float x = (float) -width / 2;

        var bgBuffer = consumers.getBuffer(RenderType.textBackgroundSeeThrough());
        var bgColour = ((int) (Minecraft.getInstance().options.getBackgroundOpacity(0.25F) * 255F)) << 24;
        bgBuffer.addVertex(matrix4f, x - 1, -1f, 0).setColor(bgColour).setLight(LightTexture.FULL_BRIGHT);
        bgBuffer.addVertex(matrix4f, x - 1, 10f, 0).setColor(bgColour).setLight(LightTexture.FULL_BRIGHT);
        bgBuffer.addVertex(matrix4f, x + width, 10f, 0).setColor(bgColour).setLight(LightTexture.FULL_BRIGHT);
        bgBuffer.addVertex(matrix4f, x + width, -1f, 0).setColor(bgColour).setLight(LightTexture.FULL_BRIGHT);

        RenderSystem.disableDepthTest();
        RenderSystem.depthFunc(GL11.GL_ALWAYS);

        if (label.seeThrough) {
            Minecraft.getInstance().font.drawInBatch(label.text, x, 0, 0xFF_FFFFFF, false,
                    matrix4f, consumers, Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);

            Minecraft.getInstance().font.drawInBatch(label.text, x, 0, 0xFF_FFFFFF, false,
                    matrix4f, consumers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        } else {
            Minecraft.getInstance().font.drawInBatch(label.text, x, 0, 0x20_FFFFFF, false,
                    matrix4f, consumers, Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);

            Minecraft.getInstance().font.drawInBatch(label.text, x, 0, 0xFF_FFFFFF, false,
                    matrix4f, consumers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        }
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableDepthTest();


        pose.popPose();
    }

    /////////////////////
    // WORLD RENDERING //
    /////////////////////

    /**
     * Render highlight boxes around found container positions.
     *
     * <p>Uses the event's poseStack, which already contains the correct projection matrix and
     * camera rotation/translation. Vertices are specified in world coordinates — the poseStack's
     * model-view matrix handles the camera transform automatically.
     */
    private static void renderBoxes(Camera camera, PoseStack poseStack, float partialTick, float progress) {
        // Build the vertex data in world space
        var tesselator = Tesselator.getInstance();
        var builder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        if (trackingActive && !trackedResults.isEmpty()) {
            // Multi-item tracking mode: each item gets its own colour with sine-wave alpha pulsing
            float time = (getTicksSinceSearch() + partialTick) * 0.05f;
            float alphaBase = 0.5f + 0.5f * Mth.sin(time * 2.0f);

            for (TrackedResult tr : trackedResults) {
                int color = red.jackf.whereisit.client.tracking.TrackingState.getTrackedColor(tr.trackedItem());
                int r = FastColor.ARGB32.red(color);
                int g = FastColor.ARGB32.green(color);
                int b = FastColor.ARGB32.blue(color);
                int a = (int) (alphaBase * 255);

                emitBoxVertices(tr.result().pos(), builder, 1.0f, r, g, b, a);
                for (BlockPos otherPos : tr.result().otherPositions()) {
                    emitBoxVertices(otherPos, builder, 1.0f, r, g, b, a);
                }
            }
        } else if (!results.isEmpty()) {
            // Original single-colour gradient fadeout mode
            var alpha = trackingActive ? 0.85f : (1 - (progress / 2f));
            var colour = CurrentGradientHolder.getColour(getBaseProgress(getTicksSinceSearch(), partialTick));
            var scale = trackingActive ? 1.0f : easingFunc(progress);

            final int r = FastColor.ARGB32.red(colour);
            final int g = FastColor.ARGB32.green(colour);
            final int b = FastColor.ARGB32.blue(colour);
            final int a = (int) (alpha * 255);

            for (SearchResult result : results.values()) {
                emitBoxVertices(result.pos(), builder, scale, r, g, b, a);
                for (BlockPos otherPos : result.otherPositions()) {
                    emitBoxVertices(otherPos, builder, scale, r, g, b, a);
                }
            }
        }

        // Apply the event's poseStack model-view matrix so world-space vertices are correctly transformed
        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.getModelViewStack().set(poseStack.last().pose());
        RenderSystem.applyModelViewMatrix();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableBlend();
        RenderSystem.depthFunc(GL11.GL_ALWAYS);

        BufferUploader.drawWithShader(builder.buildOrThrow());

        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableBlend();
    }

    /**
     * Emit vertices for a wireframe-style highlight box at the given block position.
     *
     * <p>Coordinates are in world space (block centre). The caller must ensure the correct
     * model-view and projection matrices are active before drawing.
     * At scale = 1.0 the box exactly fills the block from (pos) to (pos + 1).
     */
    private static void emitBoxVertices(BlockPos pos,
                                        VertexConsumer consumer,
                                        float scale,
                                        int r, int g, int b, int a) {
        // Block centre in world coordinates
        final double cx = pos.getX() + 0.5;
        final double cy = pos.getY() + 0.5;
        final double cz = pos.getZ() + 0.5;
        final float half = scale * 0.5f;

        final float x0 = (float) (cx - half);
        final float x1 = (float) (cx + half);
        final float y0 = (float) (cy - half);
        final float y1 = (float) (cy + half);
        final float z0 = (float) (cz - half);
        final float z1 = (float) (cz + half);

        // -Z face
        consumer.addVertex(x0, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(x0, y1, z0).setColor(r, g, b, a);
        consumer.addVertex(x1, y1, z0).setColor(r, g, b, a);
        consumer.addVertex(x1, y0, z0).setColor(r, g, b, a);

        // +Z face
        consumer.addVertex(x0, y0, z1).setColor(r, g, b, a);
        consumer.addVertex(x1, y0, z1).setColor(r, g, b, a);
        consumer.addVertex(x1, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(x0, y1, z1).setColor(r, g, b, a);

        // -Y face
        consumer.addVertex(x0, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(x1, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(x1, y0, z1).setColor(r, g, b, a);
        consumer.addVertex(x0, y0, z1).setColor(r, g, b, a);

        // +Y face
        consumer.addVertex(x0, y1, z0).setColor(r, g, b, a);
        consumer.addVertex(x0, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(x1, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(x1, y1, z0).setColor(r, g, b, a);

        // -X face
        consumer.addVertex(x0, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(x0, y0, z1).setColor(r, g, b, a);
        consumer.addVertex(x0, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(x0, y1, z0).setColor(r, g, b, a);

        // +X face
        consumer.addVertex(x1, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(x1, y1, z0).setColor(r, g, b, a);
        consumer.addVertex(x1, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(x1, y0, z1).setColor(r, g, b, a);
    }

    public static long getTicksSinceSearch() {
        return ticksSinceSearch;
    }

    public static void incrementTicksSinceSearch() {
        ticksSinceSearch++;
    }

    public static void resetSearchTime() {
        ticksSinceSearch = 0;
    }
}
