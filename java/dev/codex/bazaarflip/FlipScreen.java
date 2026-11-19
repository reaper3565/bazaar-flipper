package dev.codex.bazaarflip;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The window that lists flips. Read-only: it only displays numbers.
 * Click a tab, type to search, click a flip and type a quantity to see batch totals.
 */
public final class FlipScreen extends Screen {
    private static final String[] TABS = {
            "Craft → NPC", "Buy → NPC", "Shard fusions", "Craft → Bazaar", "Craft → Auction", "Bazaar → Bazaar"
    };
    private static final String[] SHORT_TABS = {
            "Craft→NPC", "Buy→NPC", "Shards", "Craft→BZ", "Craft→AH", "BZ→BZ"
    };
    private static final String[] DESCRIPTIONS = {
            "Craft inputs → NPC • hourly coins = profit per craft × estimated crafts/hour",
            "Buy order → NPC • hourly coins = profit per item × estimated items/hour",
            "Shard machine estimates • hourly coins = profit per fusion × estimated fusions/hour",
            "Ranked by 7-day instant buys • hourly coins = profit per craft × estimated crafts/hour",
            "Non-Bazaar outputs only • includes material and unlock requirements",
            "Buy order → sell offer • after-tax estimates; red values are losses at current prices"
    };
    private static final String[] RATE_LABELS = {
            "CRAFTS/HR", "UNITS/HR", "FUS./HR", "BUYS/7D", "CRAFTS/HR", "UNITS/HR"
    };

    private static final int ROW_H = 11;
    private static final int DETAIL_H = 62;
    private static final int CHAR_W = 6;

    private static final int C_DIM = 0xA0000000;
    private static final int C_PANEL = 0xF0111827;
    private static final int C_BORDER = 0xFF3B4A63;
    private static final int C_TAB_ON = 0xFF2D5A8C;
    private static final int C_TAB_OFF = 0xFF1C2638;
    private static final int C_TAB_HOVER = 0xFF26344D;
    private static final int C_ROW_ALT = 0x18FFFFFF;
    private static final int C_ROW_HOVER = 0x38FFFFFF;
    private static final int C_ROW_SEL = 0x6044AAFF;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_DIMTEXT = 0xFFA0AEC0;
    private static final int C_GOLD = 0xFFFFD166;
    private static final int C_GREEN = 0xFF55FF77;
    private static final int C_RED = 0xFFFF5555;

    // remembered between openings
    private static int lastTab = 0;
    private static String lastSearch = "";
    private static String lastAmount = "";
    private static String lastSelected = "";

    private int tab = lastTab;
    private int scroll = 0;
    private int panelX, panelY, panelW, panelH;
    private final int[] tabLeft = new int[TABS.length];
    private final int[] tabWidth = new int[TABS.length];
    private EditBox searchBox;
    private EditBox amountBox;
    private int amountX;
    private String selectedKey = lastSelected;
    private String searchValue = lastSearch;
    private String amountValue = lastAmount;

    public FlipScreen() {
        super(Component.literal("Bazaar Craft Flipper"));
    }

    // ------------------------------------------------------------------ setup

    @Override
    public void init() {
        panelW = Math.min(width - 20, 860);
        panelH = Math.min(height - 20, 420);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        String label = "Calculate totals for quantity →";
        int amountW = 110;
        amountX = panelX + panelW - 8 - amountW;
        int labelX = amountX - label.length() * CHAR_W - 6;
        int searchW = Math.max(80, labelX - 8 - (panelX + 8));

        searchBox = new EditBox(font, panelX + 8, panelY + 38, searchW, 18, Component.literal("Search flips"));
        searchBox.setHint(Component.literal("Search item or recipe…"));
        searchBox.setMaxLength(48);
        searchBox.setValue(searchValue);
        addRenderableWidget(searchBox);

        amountBox = new EditBox(font, amountX, panelY + 38, amountW, 18, Component.literal("Amount"));
        amountBox.setHint(Component.literal("Amount (default 1)"));
        amountBox.setMaxLength(12);
        amountBox.setValue(amountValue);
        addRenderableWidget(amountBox);

        layoutTabs();
        if (!FlipData.loaded && !FlipData.fetching) FlipData.refreshAsync();
    }

    @Override
    public void removed() {
        lastTab = tab;
        lastSearch = searchValue;
        lastAmount = amountValue;
        lastSelected = selectedKey;
        super.removed();
    }

    private boolean narrow() {
        return panelW < 620;
    }

    private void layoutTabs() {
        String[] labels = narrow() ? SHORT_TABS : TABS;
        int gap = 3;
        int totalChars = 0;
        for (String s : labels) totalChars += s.length() + 3;
        int avail = panelW - 16 - gap * (labels.length - 1);
        double unit = (double) avail / totalChars;
        int x = panelX + 8;
        for (int i = 0; i < labels.length; i++) {
            int w = (int) Math.round((labels[i].length() + 3) * unit);
            tabLeft[i] = x;
            tabWidth[i] = w;
            x += w + gap;
        }
    }

    private int tabTop() {
        return panelY + 20;
    }

    private int rowsTop() {
        return panelY + 88;
    }

    private int detailTop() {
        return panelY + panelH - DETAIL_H - 14;
    }

    private int rowsBottom() {
        return detailTop() - 4;
    }

    // ------------------------------------------------------------------ data

    private List<FlipData.Flip> source() {
        return switch (tab) {
            case 0 -> FlipData.craftNpc;
            case 1 -> FlipData.buyNpc;
            case 2 -> FlipData.fusions;
            case 3 -> FlipData.craftBazaar;
            case 4 -> FlipData.craftAuction;
            default -> FlipData.bazaarOrderFlips;
        };
    }

    private List<FlipData.Flip> currentRows() {
        List<FlipData.Flip> src = source();
        String q = searchValue.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return src;
        List<FlipData.Flip> out = new ArrayList<>();
        for (FlipData.Flip f : src) {
            if (matches(q, f)) out.add(f);
        }
        return out;
    }

    private static boolean matches(String q, FlipData.Flip f) {
        return f.name().toLowerCase(Locale.ROOT).contains(q)
                || f.detail().toLowerCase(Locale.ROOT).contains(q)
                || f.requirements().toLowerCase(Locale.ROOT).contains(q);
    }

    private static String flipKey(FlipData.Flip f) {
        return f.name() + "|" + f.detail();
    }

    private FlipData.Flip findSelected() {
        if (selectedKey.isEmpty()) return null;
        for (FlipData.Flip f : source()) {
            if (flipKey(f).equals(selectedKey)) return f;
        }
        return null;
    }

    private String emptyMessage() {
        if (!FlipData.loaded) return FlipData.status;
        if (tab == 2 && FlipData.fusionState == 0) return "Fetching shard data • calculating…";
        if (tab == 2 && FlipData.fusionState == 2) return "Shard fusion data unavailable. Press R to retry.";
        if (tab == 4 && FlipData.auctionState == 0) return "Fetching auction craft data • calculating…";
        if (tab == 4 && FlipData.auctionState == 2) return "Auction craft data unavailable. Press R to retry.";
        if (!searchValue.isBlank() && !source().isEmpty()) return "No flips match that search.";
        return "No profitable flips at current prices.";
    }

    /** Accepts 1500, 1,500, 1.5k, 2m, 3b. Anything unusable becomes 1. */
    private static double parseAmount(String raw) {
        if (raw == null) return 1;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace(" ", "");
        if (s.isEmpty()) return 1;
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        if (last == 'k') mult = 1e3;
        else if (last == 'm') mult = 1e6;
        else if (last == 'b') mult = 1e9;
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try {
            double v = Double.parseDouble(s) * mult;
            return v > 1e-6 && !Double.isInfinite(v) ? v : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String qtyText(double q) {
        if (q == Math.rint(q) && q < 1e15) return String.format(Locale.US, "%,d", (long) q);
        return String.format(Locale.US, "%.2f", q);
    }

    private static String signed(double v) {
        return (v >= 0 ? "+" : "-") + FlipData.compact(Math.abs(v));
    }

    private static String[] wrap(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        if (maxChars < 8) maxChars = 8;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            while (word.length() > maxChars) {
                if (line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, maxChars));
                word = word.substring(maxChars);
            }
            if (line.length() + word.length() + (line.length() > 0 ? 1 : 0) > maxChars) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines.toArray(new String[0]);
    }

    private static String fit(String s, int maxChars) {
        if (maxChars < 4) return "";
        return s.length() <= maxChars ? s : s.substring(0, maxChars - 3) + "...";
    }

    // ------------------------------------------------------------------ drawing

    private void rect(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y2, color);
    }

    private void draw(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(font, s, x, y, color);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        searchValue = searchBox.getValue();
        amountValue = amountBox.getValue();
        layoutTabs();

        rect(g, 0, 0, width, height, C_DIM);
        rect(g, panelX, panelY, panelX + panelW, panelY + panelH, C_PANEL);
        rect(g, panelX, panelY, panelX + panelW, panelY + 1, C_BORDER);
        rect(g, panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, C_BORDER);
        rect(g, panelX, panelY, panelX + 1, panelY + panelH, C_BORDER);
        rect(g, panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, C_BORDER);

        draw(g, "Bazaar Craft Flipper", panelX + 8, panelY + 6, C_GOLD);
        if (FlipData.fetching) {
            String s = "Refreshing…";
            draw(g, s, panelX + panelW - 8 - s.length() * CHAR_W, panelY + 6, C_DIMTEXT);
        }

        drawTabs(g, mouseX, mouseY);

        String label = "Calculate totals for quantity →";
        draw(g, label, amountX - label.length() * CHAR_W - 6, panelY + 43, C_DIMTEXT);

        int maxChars = (panelW - 16) / CHAR_W;
        draw(g, fit(DESCRIPTIONS[tab], maxChars), panelX + 8, panelY + 62, C_DIMTEXT);

        drawHeader(g);
        drawRows(g, mouseX, mouseY);
        drawDetail(g);
        drawFooter(g);

        // draws the two text boxes on top of everything above
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void drawTabs(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String[] labels = narrow() ? SHORT_TABS : TABS;
        int top = tabTop();
        for (int i = 0; i < labels.length; i++) {
            int x = tabLeft[i];
            int w = tabWidth[i];
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= top && mouseY < top + 14;
            int color = i == tab ? C_TAB_ON : hover ? C_TAB_HOVER : C_TAB_OFF;
            rect(g, x, top, x + w, top + 14, color);
            int textX = x + Math.max(2, (w - labels[i].length() * CHAR_W) / 2);
            draw(g, labels[i], textX, top + 3, C_TEXT);
        }
    }

    private int[] columnX() {
        double[] f = {0.40, 0.52, 0.64, 0.76, 0.88};
        int[] xs = new int[f.length];
        for (int i = 0; i < f.length; i++) xs[i] = panelX + (int) (panelW * f[i]);
        return xs;
    }

    private void drawHeader(GuiGraphicsExtractor g) {
        int y = panelY + 76;
        int[] xs = columnX();
        boolean wide = panelW >= 710;
        draw(g, "FLIP", panelX + 8, y, C_DIMTEXT);
        draw(g, wide ? "PROFIT / UNIT" : "PROFIT", xs[0], y, C_DIMTEXT);
        draw(g, wide ? "COINS / HR" : "COINS/HR", xs[1], y, C_DIMTEXT);
        draw(g, wide ? "COST / UNIT" : "COST", xs[2], y, C_DIMTEXT);
        draw(g, wide ? "RETURN / UNIT" : "RETURN", xs[3], y, C_DIMTEXT);
        draw(g, RATE_LABELS[tab], xs[4], y, C_DIMTEXT);
        rect(g, panelX + 4, y + 10, panelX + panelW - 4, y + 11, C_BORDER);
    }

    private void drawRows(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<FlipData.Flip> rows = currentRows();
        int top = rowsTop();
        int bottom = rowsBottom();
        int visible = Math.max(1, (bottom - top) / ROW_H);
        int maxScroll = Math.max(0, rows.size() - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        if (rows.isEmpty()) {
            draw(g, fit(emptyMessage(), (panelW - 16) / CHAR_W), panelX + 8, top + 4, C_DIMTEXT);
            return;
        }

        int[] xs = columnX();
        int nameChars = (xs[0] - panelX - 8 - 6) / CHAR_W;
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            if (idx >= rows.size()) break;
            FlipData.Flip f = rows.get(idx);
            int y = top + i * ROW_H;

            boolean hover = mouseX >= panelX + 4 && mouseX < panelX + panelW - 10
                    && mouseY >= y && mouseY < y + ROW_H;
            boolean selected = flipKey(f).equals(selectedKey);
            if (selected) rect(g, panelX + 4, y, panelX + panelW - 10, y + ROW_H, C_ROW_SEL);
            else if (hover) rect(g, panelX + 4, y, panelX + panelW - 10, y + ROW_H, C_ROW_HOVER);
            else if (idx % 2 == 0) rect(g, panelX + 4, y, panelX + panelW - 10, y + ROW_H, C_ROW_ALT);

            int profitColor = f.profit() >= 0 ? C_GREEN : C_RED;
            int hourlyColor = f.hourlyProfit() >= 0 ? C_GOLD : C_RED;
            draw(g, (idx + 1) + ". " + fit(f.name(), nameChars - 4), panelX + 8, y + 2, C_TEXT);
            draw(g, signed(f.profit()), xs[0], y + 2, profitColor);
            draw(g, signed(f.hourlyProfit()), xs[1], y + 2, hourlyColor);
            draw(g, FlipData.compact(f.cost()), xs[2], y + 2, C_TEXT);
            draw(g, FlipData.compact(f.revenue()), xs[3], y + 2, C_TEXT);
            draw(g, FlipData.compact(tab == 3 ? f.volume() : f.rate()), xs[4], y + 2, C_DIMTEXT);
        }

        if (maxScroll > 0) {
            int trackX = panelX + panelW - 8;
            int trackH = bottom - top;
            int thumbH = Math.max(12, trackH * visible / rows.size());
            int thumbY = top + (trackH - thumbH) * scroll / maxScroll;
            rect(g, trackX, top, trackX + 3, bottom, 0x30FFFFFF);
            rect(g, trackX, thumbY, trackX + 3, thumbY + thumbH, 0xA0FFFFFF);
        }
    }

    private void drawDetail(GuiGraphicsExtractor g) {
        int top = detailTop();
        int x = panelX + 8;
        int chars = (panelW - 16) / CHAR_W;
        rect(g, panelX + 4, top - 2, panelX + panelW - 4, top - 1, C_BORDER);

        FlipData.Flip sel = findSelected();
        if (sel == null) {
            draw(g, "CLICK A FLIP TO CALCULATE", x, top + 2, C_GOLD);
            int y = top + 14;
            for (String line : wrap("Click a flip, enter quantity above: see batch profit and estimated "
                    + "coins/hour at that quantity. Hourly estimate uses recent market throughput; "
                    + "fills and rates can vary.", chars)) {
                draw(g, line, x, y, C_DIMTEXT);
                y += 10;
                if (y > top + DETAIL_H - 8) break;
            }
            return;
        }

        double qty = parseAmount(amountValue);
        double batch = sel.profit() * qty;
        double perHour = sel.profit() * Math.min(qty, sel.rate());
        String head = "Selected: " + sel.name() + " • Qty " + qtyText(qty) + " = " + signed(batch)
                + " batch • est. " + signed(perHour) + " coins/hr at this quantity";
        int y = top + 2;
        draw(g, fit(head, chars), x, y, batch >= 0 ? C_GREEN : C_RED);
        y += 11;

        int limitY = top + DETAIL_H - 8;
        for (String line : wrap(sel.detail(), chars)) {
            if (y > limitY) return;
            draw(g, line, x, y, C_TEXT);
            y += 10;
        }
        String req = sel.requirements();
        if (req.isBlank() && tab == 4) req = "Requirements unavailable";
        if (!req.isBlank()) {
            for (String line : wrap(req, chars)) {
                if (y > limitY) return;
                draw(g, line, x, y, C_DIMTEXT);
                y += 10;
            }
        }
    }

    private void drawFooter(GuiGraphicsExtractor g) {
        int y = panelY + panelH - 11;
        String keys = "1-6 tabs • R refresh • Esc close";
        draw(g, keys, panelX + panelW - 8 - keys.length() * CHAR_W, y, C_DIMTEXT);
        int chars = (panelW - 16 - keys.length() * CHAR_W - 12) / CHAR_W;
        draw(g, fit(FlipData.status, chars), panelX + 8, y, C_DIMTEXT);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0) return false;

        double mx = event.x();
        double my = event.y();
        layoutTabs();
        int top = tabTop();
        for (int i = 0; i < TABS.length; i++) {
            if (mx >= tabLeft[i] && mx < tabLeft[i] + tabWidth[i] && my >= top && my < top + 14) {
                tab = i;
                scroll = 0;
                setFocused((GuiEventListener) null);
                return true;
            }
        }

        if (mx >= panelX + 4 && mx < panelX + panelW - 10 && my >= rowsTop() && my < rowsBottom()) {
            int idx = scroll + (int) ((my - rowsTop()) / ROW_H);
            List<FlipData.Flip> rows = currentRows();
            if (idx >= 0 && idx < rows.size()) {
                selectedKey = flipKey(rows.get(idx));
                setFocused((GuiEventListener) null);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scroll -= (int) Math.signum(scrollY) * 3;
            if (scroll < 0) scroll = 0;
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean typing = getFocused() instanceof EditBox;
        if (!typing) {
            if (key == GLFW.GLFW_KEY_R) {
                FlipData.refreshAsync();
                return true;
            }
            if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_6) {
                tab = key - GLFW.GLFW_KEY_1;
                scroll = 0;
                return true;
            }
            if (key == GLFW.GLFW_KEY_DOWN) {
                scroll++;
                return true;
            }
            if (key == GLFW.GLFW_KEY_UP) {
                scroll = Math.max(0, scroll - 1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_PAGE_DOWN) {
                scroll += 10;
                return true;
            }
            if (key == GLFW.GLFW_KEY_PAGE_UP) {
                scroll = Math.max(0, scroll - 10);
                return true;
            }
        }
        return super.keyPressed(event);
    }
}
