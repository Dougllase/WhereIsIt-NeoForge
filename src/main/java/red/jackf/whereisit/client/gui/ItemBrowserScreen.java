package red.jackf.whereisit.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import red.jackf.whereisit.client.compat.LitematicaBridge;
import red.jackf.whereisit.client.data.ItemEntry;
import red.jackf.whereisit.client.tracking.ContainerTracker;
import red.jackf.whereisit.client.tracking.ItemAggregator;
import red.jackf.whereisit.client.tracking.TrackingState;
import red.jackf.whereisit.config.WhereIsItConfig;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A fullscreen GUI listing every distinct item found in the player's recorded containers within the tracking range,
 * displayed as a sortable three-column table (Item | Source | Distribution) with search, source filter, and
 * per-item tracking toggle buttons.
 */
public class ItemBrowserScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_HEIGHT = 24;
    private static final int FILTER_BAR_HEIGHT = 24;
    private static final int TITLE_HEIGHT = 16;
    private static final int PAD = 8;
    private static final int ICON_SIZE = 16;
    private static final int TRACK_BTN_WIDTH = 40;
    private static final int CLEAR_BTN_WIDTH = 50;
    private static final int SCHEMATIC_BTN_WIDTH = 40;

    // Column widths (will be recalculated based on screen size)
    private int colItemWidth;
    private int colSourceWidth;
    private int colDistWidth;

    private EditBox searchBox;
    private List<ItemEntry> allItems = List.of();
    private List<ItemEntry> filtered = List.of();
    private int scrollOffset = 0;
    private boolean clearConfirm = false;
    private long clearConfirmTick = 0;

    // Panel bounds
    private int panelX, panelY, panelW, panelH;
    private int listX, listY, listW, listH;
    private int visibleRows;

    // Sort state
    private SortColumn sortColumn = SortColumn.DISTRIBUTION;
    private boolean sortAscending = false;

    // Chinese-aware collator for pinyin sorting
    private static final Collator CHINESE_COLLATOR = Collator.getInstance(Locale.CHINESE);

    // Source filter state
    private List<String> allSources = List.of();
    private Set<String> activeSources = new LinkedHashSet<>();

    // Hover state
    private int hoveredRow = -1;

    private enum SortColumn {
        ITEM, DISTRIBUTION
    }

    public ItemBrowserScreen() {
        super(Component.translatable("gui.whereisit.browser.title"));
    }

    @Override
    protected void init() {
        assert this.minecraft != null;
        var level = this.minecraft.level;
        var player = this.minecraft.player;
        if (level != null && player != null) {
            int range = WhereIsItConfig.INSTANCE.instance().getCommon().trackingRangeBlocks;
            allItems = ItemAggregator.aggregateWithModInfo(level.dimension(), player.position(), range);
        } else {
            allItems = List.of();
        }

        // Collect all source mod names
        Set<String> sources = new LinkedHashSet<>();
        for (ItemEntry entry : allItems) {
            sources.add(entry.sourceModName());
        }
        allSources = List.copyOf(sources);
        activeSources.addAll(sources);

        // Panel sizing
        panelW = Math.min(520, this.width - 40);
        int bodyH = Math.min(300, this.height - 80);
        panelH = TITLE_HEIGHT + HEADER_HEIGHT + bodyH + FILTER_BAR_HEIGHT;
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        // List area
        listX = panelX + PAD;
        listY = panelY + TITLE_HEIGHT + HEADER_HEIGHT;
        listW = panelW - PAD * 2;
        listH = bodyH;
        visibleRows = Math.max(1, (listH - ROW_HEIGHT) / ROW_HEIGHT); // -1 for header row

        // Column widths — source needs more space for translated mod names
        colSourceWidth = 120;
        colDistWidth = 130;
        colItemWidth = listW - colSourceWidth - colDistWidth - TRACK_BTN_WIDTH;

        // Search box (leave room for clear + schematic buttons)
        int searchWidth = panelW - PAD * 2 - CLEAR_BTN_WIDTH - SCHEMATIC_BTN_WIDTH - 8;
        searchBox = new EditBox(this.font, panelX + PAD, panelY + TITLE_HEIGHT + 6,
                searchWidth, 12, Component.translatable("gui.whereisit.browser.searchHint"));
        searchBox.setHint(Component.translatable("gui.whereisit.browser.searchHint"));
        searchBox.setResponder(this::onSearchChanged);
        addWidget(searchBox);

        applyFilterAndSort();
    }

    private void onSearchChanged(String query) {
        applyFilterAndSort();
        scrollOffset = 0;
    }

    private void applyFilterAndSort() {
        String q = searchBox != null ? searchBox.getValue().trim().toLowerCase() : "";

        // Filter by search and source
        filtered = new ArrayList<>();
        for (ItemEntry entry : allItems) {
            if (!activeSources.contains(entry.sourceModName())) continue;
            if (!q.isEmpty() && !entry.sample().getHoverName().getString().toLowerCase().contains(q)) continue;
            filtered.add(entry);
        }

        // Sort: group by source (Minecraft first), then by sort column within group
        filtered.sort((a, b) -> {
            // Group by source: Minecraft always first
            boolean aMinecraft = "Minecraft".equals(a.sourceModName());
            boolean bMinecraft = "Minecraft".equals(b.sourceModName());
            if (aMinecraft && !bMinecraft) return -1;
            if (!aMinecraft && bMinecraft) return 1;

            // Within same source group, sort by source name first to keep groups together
            int sourceCmp = a.sourceModName().compareToIgnoreCase(b.sourceModName());
            if (sourceCmp != 0) return sourceCmp;

            // Within group, sort by the active sort column
            int cmp;
            switch (sortColumn) {
                case ITEM:
                    cmp = CHINESE_COLLATOR.compare(
                            a.sample().getHoverName().getString(),
                            b.sample().getHoverName().getString());
                    break;
                case DISTRIBUTION:
                default:
                    cmp = Integer.compare(a.totalItems(), b.totalItems());
                    break;
            }
            return sortAscending ? cmp : -cmp;
        });
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Panel background
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xC0_101010);
        graphics.renderOutline(panelX, panelY, panelW, panelH, 0xFF_404040);

        // Title
        String titleText = Component.translatable("gui.whereisit.browser.titlebar").getString();
        graphics.drawCenteredString(this.font, titleText, panelX + panelW / 2, panelY + 4, 0xFF_FFFF00);

        // Search box
        searchBox.render(graphics, mouseX, mouseY, partialTick);

        // Schematic button (between search and clear)
        int schematicBtnX = panelX + panelW - PAD - CLEAR_BTN_WIDTH - SCHEMATIC_BTN_WIDTH - 4;
        int schematicBtnY = panelY + TITLE_HEIGHT + 4;
        int schematicBtnH = 16;
        boolean litematicaLoaded = LitematicaBridge.isLitematicaLoaded();
        int schemBgColor = litematicaLoaded ? 0xFF20_2040 : 0xFF30_3030;
        int schemTextColor = litematicaLoaded ? 0xFF_55AAFF : 0xFF_666666;
        String schemLabel = Component.translatable("gui.whereisit.browser.schematic").getString();
        graphics.fill(schematicBtnX, schematicBtnY, schematicBtnX + SCHEMATIC_BTN_WIDTH, schematicBtnY + schematicBtnH, schemBgColor);
        graphics.drawCenteredString(this.font, schemLabel, schematicBtnX + SCHEMATIC_BTN_WIDTH / 2, schematicBtnY + 4, schemTextColor);

        // Clear records button (top-right of header)
        int clearBtnX = panelX + panelW - PAD - CLEAR_BTN_WIDTH;
        int clearBtnY = panelY + TITLE_HEIGHT + 4;
        int clearBtnH = 16;
        String clearLabel = clearConfirm
                ? Component.translatable("gui.whereisit.browser.clear.confirm").getString()
                : Component.translatable("gui.whereisit.browser.clear").getString();
        int clearBgColor = clearConfirm ? 0xFF80_2020 : 0xFF30_3030;
        int clearTextColor = clearConfirm ? 0xFF_FF5555 : 0xFF_CCCCCC;
        graphics.fill(clearBtnX, clearBtnY, clearBtnX + CLEAR_BTN_WIDTH, clearBtnY + clearBtnH, clearBgColor);
        graphics.drawCenteredString(this.font, clearLabel, clearBtnX + CLEAR_BTN_WIDTH / 2, clearBtnY + 4, clearTextColor);

        // Reset confirm state after 3 seconds
        if (clearConfirm && minecraft != null && minecraft.level != null
                && minecraft.level.getGameTime() - clearConfirmTick > 60) {
            clearConfirm = false;
        }

        // Column header
        int headerY = listY;
        int x = listX;

        // Item column header (clickable for sort)
        String itemHeader = Component.translatable("gui.whereisit.browser.column.item").getString();
        if (sortColumn == SortColumn.ITEM) {
            itemHeader += sortAscending ? " \u2191" : " \u2193";
        }
        graphics.drawString(this.font, itemHeader, x + ICON_SIZE + 4, headerY + 7,
                sortColumn == SortColumn.ITEM ? 0xFF_FFFF00 : 0xFF_FFFFFF, true);

        // Source column header
        x += colItemWidth;
        String sourceHeader = Component.translatable("gui.whereisit.browser.column.source").getString();
        graphics.drawString(this.font, sourceHeader, x + 4, headerY + 7, 0xFF_CCCCCC, true);

        // Distribution column header (clickable for sort)
        x += colSourceWidth;
        String distHeader = Component.translatable("gui.whereisit.browser.column.distribution").getString();
        if (sortColumn == SortColumn.DISTRIBUTION) {
            distHeader += sortAscending ? " \u2191" : " \u2193";
        }
        graphics.drawString(this.font, distHeader, x + 4, headerY + 7,
                sortColumn == SortColumn.DISTRIBUTION ? 0xFF_FFFF00 : 0xFF_FFFFFF, true);

        // Track column header
        x += colDistWidth;
        String trackHeader = Component.translatable("gui.whereisit.browser.track").getString();
        graphics.drawString(this.font, trackHeader, x + 4, headerY + 7, 0xFF_AAAAAA, true);

        // Separator line
        graphics.fill(listX, headerY + ROW_HEIGHT - 1, listX + listW, headerY + ROW_HEIGHT, 0xFF_404040);

        // Data rows
        hoveredRow = -1;
        int rowStartY = headerY + ROW_HEIGHT;
        for (int i = 0; i < visibleRows; i++) {
            int index = scrollOffset + i;
            if (index >= filtered.size()) break;
            int rowY = rowStartY + i * ROW_HEIGHT;

            // Check hover
            boolean hovered = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (hovered) hoveredRow = i;

            // Row background
            int bgColor = hovered ? 0xA0_505050 : (index % 2 == 0 ? 0xA0_181818 : 0xA0_282828);
            graphics.fill(listX, rowY, listX + listW, rowY + ROW_HEIGHT, bgColor);

            ItemEntry entry = filtered.get(index);
            x = listX;

            // Item column: icon + name
            boolean isTracked = TrackingState.isTracking(entry.item());
            int iconY = rowY + (ROW_HEIGHT - ICON_SIZE) / 2;
            graphics.renderItem(entry.sample(), x + 2, iconY);

            // Item name with tracking indicator
            String name = entry.sample().getHoverName().getString();
            if (isTracked) {
                graphics.drawString(this.font, Component.literal(name).withStyle(ChatFormatting.GREEN), x + ICON_SIZE + 6, rowY + 7, 0xFF_FFFFFF);
            } else {
                graphics.drawString(this.font, name, x + ICON_SIZE + 6, rowY + 7, 0xFF_FFFFFF);
            }

            // Source column
            x += colItemWidth;
            String sourceName = entry.sourceModName();
            graphics.drawString(this.font, sourceName, x + 4, rowY + 7, 0xFF_AAAAAA);

            // Distribution column
            x += colSourceWidth;
            String dist = Component.translatable("gui.whereisit.browser.distribution.format",
                    entry.totalItems(), entry.containerCount()).getString();
            graphics.drawString(this.font, dist, x + 4, rowY + 7, 0xFF_CCCCCC);

            // Track button
            x += colDistWidth;
            String btnLabel = isTracked ? "\u2713" : "...";
            int btnColor = isTracked ? 0xFF30_6030 : 0xFF30_3030;
            int btnX = x + 2;
            int btnY2 = rowY + 3;
            graphics.fill(btnX, btnY2, btnX + TRACK_BTN_WIDTH - 4, btnY2 + ROW_HEIGHT - 6, btnColor);
            int btnTextColor = isTracked ? 0xFF_55FF55 : 0xFF_AAAAAA;
            graphics.drawCenteredString(this.font, btnLabel, btnX + (TRACK_BTN_WIDTH - 4) / 2, btnY2 + 4, btnTextColor);
        }

        // Source filter bar at the bottom
        int filterY = panelY + panelH - FILTER_BAR_HEIGHT;
        graphics.fill(panelX, filterY, panelX + panelW, filterY + FILTER_BAR_HEIGHT, 0xC0_181818);
        graphics.drawString(this.font, Component.translatable("gui.whereisit.browser.filter.source").getString() + ": ",
                panelX + PAD, filterY + 6, 0xFF_AAAAAA);

        int filterX = panelX + PAD + this.font.width(Component.translatable("gui.whereisit.browser.filter.source").getString() + ": ");
        for (String source : allSources) {
            boolean active = activeSources.contains(source);
            int labelWidth = this.font.width(source) + 8;
            int fBtnX = filterX;
            int fBtnY = filterY + 2;
            int fBtnH = 16;

            // Button background
            int fBgColor = active ? 0xFF20_4020 : 0xFF40_2020;
            graphics.fill(fBtnX, fBtnY, fBtnX + labelWidth, fBtnY + fBtnH, fBgColor);
            int fTextColor = active ? 0xFF_55FF55 : 0xFF_FF5555;
            graphics.drawString(this.font, source, fBtnX + 4, fBtnY + 4, fTextColor);

            filterX += labelWidth + 4;
            if (filterX > panelX + panelW - 40) break;
        }

        // Scrollbar
        if (filtered.size() > visibleRows) {
            int totalRows = filtered.size();
            int scrollbarHeight = listH - ROW_HEIGHT;
            int scrollbarX = listX + listW - 6;
            int thumbHeight = Math.max(20, scrollbarHeight * visibleRows / totalRows);
            int thumbY = rowStartY + (scrollbarHeight - thumbHeight) * scrollOffset / Math.max(1, totalRows - visibleRows);
            graphics.fill(scrollbarX, rowStartY, scrollbarX + 4, rowStartY + scrollbarHeight, 0x40_808080);
            graphics.fill(scrollbarX, thumbY, scrollbarX + 4, thumbY + thumbHeight, 0xC0_C0C0C0);
        }

        // Empty state
        if (filtered.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.whereisit.browser.empty"),
                    panelX + panelW / 2, listY + listH / 2 - 4, 0xFF_888888);
        }

        // Footer
        graphics.drawCenteredString(this.font, Component.translatable("gui.whereisit.browser.footer"),
                panelX + panelW / 2, panelY + panelH + 6, 0xFF_AAAAAA);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        // Schematic button
        int schematicBtnX = panelX + panelW - PAD - CLEAR_BTN_WIDTH - SCHEMATIC_BTN_WIDTH - 4;
        int schematicBtnY = panelY + TITLE_HEIGHT + 4;
        int schematicBtnH = 16;
        if (mouseX >= schematicBtnX && mouseX < schematicBtnX + SCHEMATIC_BTN_WIDTH
                && mouseY >= schematicBtnY && mouseY < schematicBtnY + schematicBtnH) {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new SchematicMaterialScreen());
            }
            return true;
        }

        // Clear records button
        int clearBtnX = panelX + panelW - PAD - CLEAR_BTN_WIDTH;
        int clearBtnY = panelY + TITLE_HEIGHT + 4;
        int clearBtnH = 16;
        if (mouseX >= clearBtnX && mouseX < clearBtnX + CLEAR_BTN_WIDTH
                && mouseY >= clearBtnY && mouseY < clearBtnY + clearBtnH) {
            if (clearConfirm) {
                // Second click: actually clear
                TrackingState.stopAll();
                ContainerTracker.clearAll();
                ContainerTracker.saveIfDirty();
                allItems = List.of();
                applyFilterAndSort();
                scrollOffset = 0;
                clearConfirm = false;
            } else {
                // First click: ask for confirmation
                clearConfirm = true;
                clearConfirmTick = minecraft != null && minecraft.level != null
                        ? minecraft.level.getGameTime() : 0;
            }
            return true;
        }

        // Check column header clicks for sorting
        int headerY = listY;
        if (mouseY >= headerY && mouseY < headerY + ROW_HEIGHT) {
            int mx = (int) mouseX;
            if (mx >= listX && mx < listX + colItemWidth) {
                if (sortColumn == SortColumn.ITEM) {
                    sortAscending = !sortAscending;
                } else {
                    sortColumn = SortColumn.ITEM;
                    sortAscending = true;
                }
                applyFilterAndSort();
                return true;
            }
            if (mx >= listX + colItemWidth + colSourceWidth && mx < listX + colItemWidth + colSourceWidth + colDistWidth) {
                if (sortColumn == SortColumn.DISTRIBUTION) {
                    sortAscending = !sortAscending;
                } else {
                    sortColumn = SortColumn.DISTRIBUTION;
                    sortAscending = false; // default descending for distribution
                }
                applyFilterAndSort();
                return true;
            }
        }

        // Check data row clicks
        int rowStartY = headerY + ROW_HEIGHT;
        for (int i = 0; i < visibleRows; i++) {
            int index = scrollOffset + i;
            if (index >= filtered.size()) break;
            int rowY = rowStartY + i * ROW_HEIGHT;

            if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                int mx = (int) mouseX;

                // Track button click
                int btnX = listX + colItemWidth + colSourceWidth + colDistWidth + 2;
                if (mx >= btnX && mx < btnX + TRACK_BTN_WIDTH) {
                    ItemEntry entry = filtered.get(index);
                    toggleTracking(entry);
                    return true;
                }

                // Click on item name also toggles tracking
                if (mx >= listX && mx < listX + colItemWidth) {
                    ItemEntry entry = filtered.get(index);
                    toggleTracking(entry);
                    return true;
                }
            }
        }

        // Source filter bar clicks
        int filterY = panelY + panelH - FILTER_BAR_HEIGHT;
        if (mouseY >= filterY && mouseY < filterY + FILTER_BAR_HEIGHT) {
            int filterX = panelX + PAD + this.font.width(Component.translatable("gui.whereisit.browser.filter.source").getString() + ": ");
            for (String source : allSources) {
                int labelWidth = this.font.width(source) + 8;
                if (mouseX >= filterX && mouseX < filterX + labelWidth) {
                    if (activeSources.contains(source)) {
                        activeSources.remove(source);
                    } else {
                        activeSources.add(source);
                    }
                    applyFilterAndSort();
                    scrollOffset = 0;
                    return true;
                }
                filterX += labelWidth + 4;
                if (filterX > panelX + panelW - 40) break;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void toggleTracking(ItemEntry entry) {
        Item item = entry.item();
        if (TrackingState.isTracking(item)) {
            TrackingState.stopTracking(item);
        } else {
            TrackingState.startTracking(entry.sample());
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxRow = Math.max(0, filtered.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(maxRow, scrollOffset - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
