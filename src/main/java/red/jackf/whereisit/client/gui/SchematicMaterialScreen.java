package red.jackf.whereisit.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import red.jackf.whereisit.client.compat.LitematicaBridge;
import red.jackf.whereisit.client.tracking.ContainerTracker;
import red.jackf.whereisit.client.tracking.ContainerRecord;
import red.jackf.whereisit.client.tracking.TrackingState;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Displays the material list from the currently selected Litematica schematic placement,
 * showing readiness status by cross-referencing with WhereIsIt's container records.
 *
 * <p>Three-column table: Item | Readiness | Actions (Track + Done)</p>
 * <p>Readiness format: {@code stored(percentage%)/required}</p>
 */
public class SchematicMaterialScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_HEIGHT = 24;
    private static final int TITLE_HEIGHT = 16;
    private static final int PAD = 8;
    private static final int ICON_SIZE = 16;
    private static final int ACTION_BTN_WIDTH = 24;
    private static final int ACTION_COL_WIDTH = ACTION_BTN_WIDTH * 2 + 4; // Track + Done buttons
    private static final int READINESS_WIDTH = 150;

    // Column widths
    private int colItemWidth;

    // Data
    private List<MaterialRow> rows = List.of();
    private int scrollOffset = 0;

    // Items marked as "done" (collected) — persisted per screen session
    private final Set<Item> doneItems = new HashSet<>();

    // Panel bounds
    private int panelX, panelY, panelW, panelH;
    private int listX, listY, listW, listH;
    private int visibleRows;

    // Sort state
    private SortColumn sortColumn = SortColumn.READINESS;
    private boolean sortAscending = true;

    // Chinese-aware collator for pinyin sorting
    private static final Collator CHINESE_COLLATOR = Collator.getInstance(Locale.CHINESE);

    private enum SortColumn {
        ITEM, READINESS
    }

    /** One row in the schematic material list. */
    public record MaterialRow(ItemStack stack, int required, int stored) {
        public Item item() { return stack.getItem(); }
        /** Readiness percentage: stored / required * 100. Capped at 999. */
        public double percentage() {
            return required <= 0 ? 999.0 : Math.min((double) stored / required * 100, 999.0);
        }
    }

    public SchematicMaterialScreen() {
        super(Component.translatable("gui.whereisit.schematic.title"));
    }

    @Override
    protected void init() {
        assert this.minecraft != null;

        // Panel sizing
        panelW = Math.min(560, this.width - 40);
        int bodyH = Math.min(300, this.height - 80);
        panelH = TITLE_HEIGHT + HEADER_HEIGHT + bodyH;
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        // List area
        listX = panelX + PAD;
        listY = panelY + TITLE_HEIGHT + HEADER_HEIGHT;
        listW = panelW - PAD * 2;
        listH = bodyH;
        visibleRows = Math.max(1, (listH - ROW_HEIGHT) / ROW_HEIGHT);

        // Column widths
        colItemWidth = listW - READINESS_WIDTH - ACTION_COL_WIDTH;

        // Load data
        loadMaterialData();
    }

    private void loadMaterialData() {
        List<LitematicaBridge.SchematicMaterialEntry> entries = LitematicaBridge.getMaterialListEntries();
        if (entries.isEmpty()) {
            rows = List.of();
            return;
        }

        // Aggregate stored counts from ContainerTracker for each item
        Map<Item, Integer> storedCounts = new HashMap<>();
        var level = this.minecraft.level;
        if (level != null) {
            for (ContainerRecord record : ContainerTracker.getRecords(level.dimension())) {
                for (ItemStack stack : record.contents()) {
                    if (!stack.isEmpty()) {
                        storedCounts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                    }
                }
            }
        }

        // Build rows
        List<MaterialRow> rawRows = new ArrayList<>();
        for (LitematicaBridge.SchematicMaterialEntry entry : entries) {
            ItemStack stack = entry.stack();
            if (stack == null || stack.isEmpty()) continue;
            int stored = storedCounts.getOrDefault(stack.getItem(), 0);
            rawRows.add(new MaterialRow(stack.copy(), entry.countTotal(), stored));
        }

        rows = rawRows;
        applySort();
    }

    private void applySort() {
        List<MaterialRow> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> {
            int cmp;
            switch (sortColumn) {
                case ITEM:
                    cmp = CHINESE_COLLATOR.compare(
                            a.stack().getHoverName().getString(),
                            b.stack().getHoverName().getString());
                    break;
                case READINESS:
                default:
                    cmp = Double.compare(a.percentage(), b.percentage());
                    break;
            }
            return sortAscending ? cmp : -cmp;
        });
        rows = sorted;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Panel background
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xC0_101010);
        graphics.renderOutline(panelX, panelY, panelW, panelH, 0xFF_404040);

        // Title
        String titleText = Component.translatable("gui.whereisit.schematic.title").getString();
        String placementName = LitematicaBridge.getSelectedPlacementName();
        if (placementName != null) {
            titleText += " - " + placementName;
        }
        graphics.drawCenteredString(this.font, titleText, panelX + panelW / 2, panelY + 4, 0xFF_55AAFF);

        // Back button (top-left)
        String backLabel = "< " + Component.translatable("gui.whereisit.schematic.back").getString();
        graphics.drawString(this.font, backLabel, panelX + PAD, panelY + TITLE_HEIGHT + 6, 0xFF_AAAAAA);

        // Column header
        int headerY = listY;
        int x = listX;

        // Item column header
        String itemHeader = Component.translatable("gui.whereisit.schematic.column.item").getString();
        if (sortColumn == SortColumn.ITEM) {
            itemHeader += sortAscending ? " \u2191" : " \u2193";
        }
        graphics.drawString(this.font, itemHeader, x + ICON_SIZE + 4, headerY + 7,
                sortColumn == SortColumn.ITEM ? 0xFF_FFFF00 : 0xFF_FFFFFF, true);

        // Readiness column header
        x += colItemWidth;
        String readinessHeader = Component.translatable("gui.whereisit.schematic.column.readiness").getString();
        if (sortColumn == SortColumn.READINESS) {
            readinessHeader += sortAscending ? " \u2191" : " \u2193";
        }
        graphics.drawString(this.font, readinessHeader, x + 4, headerY + 7,
                sortColumn == SortColumn.READINESS ? 0xFF_FFFF00 : 0xFF_FFFFFF, true);

        // Action column header
        x += READINESS_WIDTH;
        graphics.drawString(this.font, Component.translatable("gui.whereisit.schematic.track").getString(),
                x + 2, headerY + 7, 0xFF_AAAAAA, true);

        // Separator line
        graphics.fill(listX, headerY + ROW_HEIGHT - 1, listX + listW, headerY + ROW_HEIGHT, 0xFF_404040);

        // Data rows
        int rowStartY = headerY + ROW_HEIGHT;
        for (int i = 0; i < visibleRows; i++) {
            int index = scrollOffset + i;
            if (index >= rows.size()) break;
            int rowY = rowStartY + i * ROW_HEIGHT;

            boolean hovered = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

            MaterialRow row = rows.get(index);
            boolean isDone = doneItems.contains(row.item());
            boolean isTracked = TrackingState.isTracking(row.item());

            // Row background — done items get a subtle green tint
            int bgColor;
            if (isDone) {
                bgColor = hovered ? 0xA0_304030 : 0xA0_1A281A;
            } else {
                bgColor = hovered ? 0xA0_505050 : (index % 2 == 0 ? 0xA0_181818 : 0xA0_282828);
            }
            graphics.fill(listX, rowY, listX + listW, rowY + ROW_HEIGHT, bgColor);

            x = listX;

            // Item column: icon + name
            int iconY = rowY + (ROW_HEIGHT - ICON_SIZE) / 2;
            graphics.renderItem(row.stack(), x + 2, iconY);

            String name = row.stack().getHoverName().getString();
            int nameColor;
            if (isDone) {
                nameColor = 0xFF_666666; // dimmed for done items
            } else if (isTracked) {
                nameColor = 0xFF_55FF55; // green for tracked
            } else {
                nameColor = 0xFF_FFFFFF;
            }
            graphics.drawString(this.font, name, x + ICON_SIZE + 6, rowY + 7, nameColor);

            // Strikethrough line for done items
            if (isDone) {
                int nameWidth = this.font.width(name);
                int lineY = rowY + 7 + this.font.lineHeight / 2;
                graphics.fill(x + ICON_SIZE + 6, lineY, x + ICON_SIZE + 6 + nameWidth, lineY + 1, 0xFF_666666);
            }

            // Readiness column
            x += colItemWidth;
            double pct = row.percentage();
            int pctInt = (int) Math.round(pct);
            String readiness = String.format("%d(%d%%)/%d", row.stored(), pctInt, row.required());
            int readinessColor;
            if (isDone) {
                readinessColor = 0xFF_666666;
            } else if (pct >= 100.0) {
                readinessColor = 0xFF_55FF55;
            } else if (pct >= 50.0) {
                readinessColor = 0xFF_FFFF55;
            } else {
                readinessColor = 0xFF_FF5555;
            }
            graphics.drawString(this.font, readiness, x + 4, rowY + 7, readinessColor);

            // Action buttons
            x += READINESS_WIDTH;
            int btnY = rowY + 3;
            int btnH = ROW_HEIGHT - 6;

            // Track button
            String trackLabel = isTracked ? "\u2713" : "...";
            int trackBg = isTracked ? 0xFF30_6030 : 0xFF30_3030;
            int trackFg = isTracked ? 0xFF_55FF55 : 0xFF_AAAAAA;
            graphics.fill(x + 2, btnY, x + 2 + ACTION_BTN_WIDTH, btnY + btnH, trackBg);
            graphics.drawCenteredString(this.font, trackLabel, x + 2 + ACTION_BTN_WIDTH / 2, btnY + 4, trackFg);

            // Done/Strikethrough button
            String doneLabel = isDone ? "\u2713" : "\u2717";
            int doneBg = isDone ? 0xFF30_6030 : 0xFF30_3030;
            int doneFg = isDone ? 0xFF_55FF55 : 0xFF_AAAAAA;
            graphics.fill(x + ACTION_BTN_WIDTH + 4, btnY, x + ACTION_BTN_WIDTH * 2 + 2, btnY + btnH, doneBg);
            graphics.drawCenteredString(this.font, doneLabel, x + ACTION_BTN_WIDTH + 4 + ACTION_BTN_WIDTH / 2, btnY + 4, doneFg);
        }

        // Scrollbar
        if (rows.size() > visibleRows) {
            int totalRows = rows.size();
            int scrollbarHeight = listH - ROW_HEIGHT;
            int scrollbarX = listX + listW - 6;
            int thumbHeight = Math.max(20, scrollbarHeight * visibleRows / totalRows);
            int thumbY = rowStartY + (scrollbarHeight - thumbHeight) * scrollOffset / Math.max(1, totalRows - visibleRows);
            graphics.fill(scrollbarX, rowStartY, scrollbarX + 4, rowStartY + scrollbarHeight, 0x40_808080);
            graphics.fill(scrollbarX, thumbY, scrollbarX + 4, thumbY + thumbHeight, 0xC0_C0C0C0);
        }

        // Empty state
        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.whereisit.schematic.empty"),
                    panelX + panelW / 2, listY + listH / 2 - 4, 0xFF_888888);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        // Back button
        String backLabel = "< " + Component.translatable("gui.whereisit.schematic.back").getString();
        int backWidth = this.font.width(backLabel);
        if (mouseX >= panelX + PAD && mouseX < panelX + PAD + backWidth
                && mouseY >= panelY + TITLE_HEIGHT + 4 && mouseY < panelY + TITLE_HEIGHT + 18) {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new ItemBrowserScreen());
            }
            return true;
        }

        // Column header clicks for sorting
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
                applySort();
                return true;
            }
            if (mx >= listX + colItemWidth && mx < listX + colItemWidth + READINESS_WIDTH) {
                if (sortColumn == SortColumn.READINESS) {
                    sortAscending = !sortAscending;
                } else {
                    sortColumn = SortColumn.READINESS;
                    sortAscending = true;
                }
                applySort();
                return true;
            }
        }

        // Data row clicks
        int rowStartY = headerY + ROW_HEIGHT;
        for (int i = 0; i < visibleRows; i++) {
            int index = scrollOffset + i;
            if (index >= rows.size()) break;
            int rowY = rowStartY + i * ROW_HEIGHT;

            if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                int mx = (int) mouseX;
                int actionX = listX + colItemWidth + READINESS_WIDTH;
                int btnY = rowY + 3;
                int btnH = ROW_HEIGHT - 6;

                // Track button
                if (mx >= actionX + 2 && mx < actionX + 2 + ACTION_BTN_WIDTH
                        && mouseY >= btnY && mouseY < btnY + btnH) {
                    MaterialRow row = rows.get(index);
                    toggleTracking(row);
                    return true;
                }

                // Done button
                if (mx >= actionX + ACTION_BTN_WIDTH + 4 && mx < actionX + ACTION_BTN_WIDTH * 2 + 2
                        && mouseY >= btnY && mouseY < btnY + btnH) {
                    MaterialRow row = rows.get(index);
                    toggleDone(row);
                    return true;
                }

                // Click on item name toggles tracking
                if (mx >= listX && mx < listX + colItemWidth) {
                    MaterialRow row = rows.get(index);
                    toggleTracking(row);
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void toggleTracking(MaterialRow row) {
        Item item = row.item();
        if (TrackingState.isTracking(item)) {
            TrackingState.stopTracking(item);
        } else {
            TrackingState.startTracking(row.stack());
        }
    }

    private void toggleDone(MaterialRow row) {
        Item item = row.item();
        if (doneItems.contains(item)) {
            doneItems.remove(item);
        } else {
            doneItems.add(item);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxRow = Math.max(0, rows.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(maxRow, scrollOffset - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
