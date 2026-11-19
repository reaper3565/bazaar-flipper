package dev.codex.bazaarflip;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads Hypixel Bazaar / item / recipe data and turns it into ranked "flips".
 * Everything here is read-only: it only reads public web APIs and never touches the game.
 */
public final class FlipData {
    private FlipData() {}

    // ------------------------------------------------------------------ types

    public record Ingredient(String id, double amount) {}

    public record Recipe(String id, String name, double output, List<Ingredient> ingredients) {}

    public record ItemInfo(String name, double npc) {}

    public record Shard(String id, String name, int amount) {}

    public record Activity(long time, double amount) {}

    /**
     * One row in the UI.
     * profit = coins per unit/craft, hourlyProfit = profit * rate, volume = instant buys / 7 days,
     * rate = estimated crafts (or units / fusions) per hour.
     */
    public record Flip(String name, String detail, String requirements,
                       double profit, double hourlyProfit, double revenue, double cost,
                       double volume, double rate) {}

    // ------------------------------------------------------------------ constants

    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
    private static final String COFLNET_URL = "https://sky.coflnet.com/api/craft/profit";
    private static final String FUSION_URL =
            "https://raw.githubusercontent.com/Campionnn/SkyShards/master/public/fusion-data.json";

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;
    private static final double WEEK_HOURS = 168.0;

    private static final Comparator<Flip> BY_HOURLY =
            Comparator.comparingDouble(Flip::hourlyProfit).reversed();
    private static final Comparator<Flip> BY_VOLUME =
            Comparator.comparingDouble(Flip::volume).reversed();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ------------------------------------------------------------------ published state (read by the screen)

    public static volatile List<Flip> craftNpc = List.of();
    public static volatile List<Flip> buyNpc = List.of();
    public static volatile List<Flip> fusions = List.of();
    public static volatile List<Flip> craftBazaar = List.of();
    public static volatile List<Flip> craftAuction = List.of();
    public static volatile List<Flip> bazaarOrderFlips = List.of();

    public static volatile String status = "Starting price tracker…";
    public static volatile int blockedMarkets = 0;
    public static volatile boolean loaded = false;
    public static volatile boolean fetching = false;

    /** 0 = pending, 1 = ok, 2 = unavailable. */
    public static volatile int fusionState = 0;
    public static volatile int auctionState = 0;

    /** Kept for compatibility with the old field name. */
    public static volatile boolean auctionDataAvailable = false;

    // ------------------------------------------------------------------ private state

    private static final AtomicBoolean FETCHING = new AtomicBoolean(false);
    private static final Map<String, double[]> PREVIOUS = new HashMap<>();
    private static final Map<String, Deque<Activity>> BUYS = new ConcurrentHashMap<>();
    private static final Map<String, Deque<Activity>> SELLS = new ConcurrentHashMap<>();
    private static volatile long trackingSince = System.currentTimeMillis();
    private static ScheduledExecutorService poller;

    private static volatile Config cfg = new Config();

    private static volatile Map<String, ItemInfo> itemsCache = Map.of();
    private static volatile long itemsAt = 0;
    private static volatile List<Recipe> recipeCache = List.of();
    private static volatile long recipeStamp = -1;

    // ------------------------------------------------------------------ scheduling

    public static synchronized void startTracker() {
        if (poller != null) return;
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Bazaar Craft Flipper poller");
            t.setDaemon(true);
            return t;
        });
        trackingSince = System.currentTimeMillis();
        status = "Starting price tracker…";
        poller.execute(FlipData::pollLoop);
    }

    private static void pollLoop() {
        try {
            refresh();
        } finally {
            ScheduledExecutorService p = poller;
            if (p != null) {
                p.schedule(FlipData::pollLoop, Math.max(15, cfg.refreshSeconds), TimeUnit.SECONDS);
            }
        }
    }

    /** Called when the player presses R. Runs in the background, never blocks the game. */
    public static void refreshAsync() {
        startTracker();
        ScheduledExecutorService p = poller;
        if (p != null) p.execute(FlipData::refresh);
    }

    // ------------------------------------------------------------------ main refresh

    public static void refresh() {
        if (!FETCHING.compareAndSet(false, true)) return;
        fetching = true;
        try {
            Config c = Config.load();
            cfg = c;
            status = "Fetching live Hypixel Bazaar data…";

            JsonObject root = getJson(BAZAAR_URL).getAsJsonObject();
            JsonObject products = root.getAsJsonObject("products");
            if (products == null) throw new IOException("Bazaar response had no products");
            Map<String, ItemInfo> items = items();
            List<Recipe> recipes = recipes();
            Map<String, Recipe> byId = new HashMap<>();
            for (Recipe r : recipes) byId.put(r.id(), r);

            long now = System.currentTimeMillis();
            recordActivity(products, now);
            Set<String> blocked = new HashSet<>();

            craftNpc = finish(computeCraftNpc(products, items, recipes, blocked, now, c), BY_HOURLY, c);
            buyNpc = finish(computeBuyNpc(products, items, blocked, now, c), BY_HOURLY, c);
            craftBazaar = finish(computeCraftBazaar(products, items, recipes, blocked, now, c), BY_VOLUME, c);
            bazaarOrderFlips = finish(computeBazaarOrders(products, items, blocked, now, c), BY_HOURLY, c);
            blockedMarkets = blocked.size();
            loaded = true;

            status = "Bazaar tabs updated • calculating shard fusions…";
            fusionState = 0;
            try {
                fusions = finish(computeFusions(products, blocked, now, c), BY_HOURLY, c);
                fusionState = 1;
            } catch (Exception e) {
                fusionState = 2;
            }

            auctionState = 0;
            auctionDataAvailable = false;
            try {
                craftAuction = finish(computeAuction(products, byId, items, blocked, now, c), BY_HOURLY, c);
                auctionState = 1;
                auctionDataAvailable = true;
            } catch (Exception e) {
                auctionState = 2;
            }

            blockedMarkets = blocked.size();
            long minutes = Math.min(60, Math.max(0, (now - trackingSince) / 60_000L));
            if (fusionState == 2) {
                status = "Bazaar tabs updated • shard data unavailable; check connection and press R.";
            } else {
                status = "Updated • blocker hid " + blockedMarkets + " suspicious markets • local activity "
                        + minutes + "/60 min (hourly rates estimated).";
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            status = "Could not load prices/recipes: " + msg + ". Press R to retry.";
        } finally {
            fetching = false;
            FETCHING.set(false);
        }
    }

    private static List<Flip> finish(List<Flip> list, Comparator<Flip> order, Config c) {
        list.sort(order);
        if (list.size() > c.maxRows) list = new ArrayList<>(list.subList(0, c.maxRows));
        return List.copyOf(list);
    }

    // ------------------------------------------------------------------ flip builders

    private static List<Flip> computeCraftNpc(JsonObject products, Map<String, ItemInfo> items,
                                              List<Recipe> recipes, Set<String> blocked, long now, Config c) {
        List<Flip> out = new ArrayList<>();
        for (Recipe r : recipes) {
            ItemInfo info = items.get(r.id());
            if (info == null || info.npc() <= 0) continue;
            double cost = ingredientBuyOrderCost(r.ingredients(), products, blocked, c);
            if (Double.isNaN(cost)) continue;
            double revenue = info.npc() * r.output();
            double profit = revenue - cost;
            if (profit <= c.minProfit) continue;
            double rate = craftRate(r, now, products);
            if (!(rate > 0)) continue;
            String name = displayName(r.id(), r.name(), items);
            out.add(new Flip(name, "Craft " + name + " → NPC", "", profit, profit * rate, revenue, cost,
                    0, rate));
        }
        return out;
    }

    private static List<Flip> computeBuyNpc(JsonObject products, Map<String, ItemInfo> items,
                                            Set<String> blocked, long now, Config c) {
        List<Flip> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : products.entrySet()) {
            String id = e.getKey();
            ItemInfo info = items.get(id);
            if (info == null || info.npc() <= 0) continue;
            JsonObject p = asObject(e.getValue());
            if (p == null) continue;
            if (marketLooksManipulated(products, id, blocked, c)) continue;
            double cost = buyOrderPrice(p);
            if (Double.isNaN(cost)) continue;
            double profit = info.npc() - cost;
            if (profit <= c.minProfit) continue;
            double rate = hourlyFlow(p, id, false, now);
            if (!(rate > 0)) continue;
            String name = displayName(id, "", items);
            out.add(new Flip(name, "Buy order → NPC", "", profit, profit * rate, info.npc(), cost,
                    weeklyInstantSells(p), rate));
        }
        return out;
    }

    private static List<Flip> computeCraftBazaar(JsonObject products, Map<String, ItemInfo> items,
                                                 List<Recipe> recipes, Set<String> blocked, long now, Config c) {
        List<Flip> out = new ArrayList<>();
        for (Recipe r : recipes) {
            JsonObject outProduct = product(products, r.id());
            if (outProduct == null) continue;
            if (weeklyInstantBuys(outProduct) < c.minWeeklyVolume) continue;
            if (marketLooksManipulated(products, r.id(), blocked, c)) continue;
            double cost = ingredientBuyOrderCost(r.ingredients(), products, blocked, c);
            if (Double.isNaN(cost)) continue;
            double sell = sellOfferPrice(outProduct);
            if (Double.isNaN(sell)) continue;
            double revenue = sell * r.output() * c.bazaarKeep();
            double profit = revenue - cost;
            if (profit <= c.minProfit) continue;
            double rate = craftBazaarRate(r, now, products, outProduct);
            if (!(rate > 0)) continue;
            String name = displayName(r.id(), r.name(), items);
            out.add(new Flip(name,
                    "Craft " + name + " → Bazaar sell offer (" + trimNumber(c.bazaarTaxPercent) + "% tax est.)",
                    "", profit, profit * rate, revenue, cost, weeklyInstantBuys(outProduct), rate));
        }
        return out;
    }

    private static List<Flip> computeBazaarOrders(JsonObject products, Map<String, ItemInfo> items,
                                                  Set<String> blocked, long now, Config c) {
        List<Flip> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : products.entrySet()) {
            String id = e.getKey();
            JsonObject p = asObject(e.getValue());
            if (p == null) continue;
            double volume = Math.min(weeklyInstantBuys(p), weeklyInstantSells(p));
            if (volume < c.minWeeklyVolume) continue;
            if (marketLooksManipulated(products, id, blocked, c)) continue;
            double buy = buyOrderPrice(p);
            double sell = sellOfferPrice(p);
            if (Double.isNaN(buy) || Double.isNaN(sell)) continue;
            double revenue = sell * c.bazaarKeep();
            double profit = revenue - buy;
            if (profit <= c.minProfit) continue;
            double rate = Math.min(hourlyFlow(p, id, false, now), hourlyFlow(p, id, true, now));
            if (!(rate > 0)) continue;
            String name = displayName(id, "", items);
            out.add(new Flip(name,
                    "Buy order → sell offer • after " + trimNumber(c.bazaarTaxPercent) + "% tax",
                    "", profit, profit * rate, revenue, buy, volume, rate));
        }
        return out;
    }

    // ------------------------------------------------------------------ shard fusions

    private record FusionRow(String a, String b, int qty) {}

    private static List<Flip> computeFusions(JsonObject products, Set<String> blocked, long now, Config c)
            throws Exception {
        JsonObject data = fusionData();
        JsonObject shardsJson = data.getAsJsonObject("shards");
        JsonObject recipesJson = data.getAsJsonObject("recipes");
        if (shardsJson == null || recipesJson == null) throw new IOException("Unexpected fusion data format");

        Map<String, Shard> shards = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : shardsJson.entrySet()) {
            JsonObject o = asObject(e.getValue());
            if (o == null) continue;
            String internal = str(o, "internal_id");
            if (internal.isEmpty()) continue;
            int amount = (int) Math.max(1, number(o, "fuse_amount", 1));
            shards.put(e.getKey(), new Shard(internal, str(o, "name"), amount));
        }

        List<Flip> out = new ArrayList<>();
        int rowsSeen = 0;
        for (Map.Entry<String, JsonElement> e : recipesJson.entrySet()) {
            Shard result = shards.get(e.getKey());
            List<FusionRow> rows = new ArrayList<>();
            collectFusionRows(e.getValue(), 1, rows);
            rowsSeen += rows.size();
            if (result == null) continue;

            JsonObject outProduct = product(products, result.id());
            if (outProduct == null) continue;
            if (weeklyInstantBuys(outProduct) < c.minWeeklyVolume) continue;
            if (marketLooksManipulated(products, result.id(), blocked, c)) continue;
            double sell = sellOfferPrice(outProduct);
            if (Double.isNaN(sell)) continue;

            for (FusionRow row : rows) {
                Shard sa = shards.get(row.a());
                Shard sb = shards.get(row.b());
                if (sa == null || sb == null) continue;
                JsonObject pa = product(products, sa.id());
                JsonObject pb = product(products, sb.id());
                if (pa == null || pb == null) continue;
                if (marketLooksManipulated(products, sa.id(), blocked, c)
                        || marketLooksManipulated(products, sb.id(), blocked, c)) continue;
                double buyA = buyOrderPrice(pa);
                double buyB = buyOrderPrice(pb);
                if (Double.isNaN(buyA) || Double.isNaN(buyB)) continue;

                double cost = buyA * sa.amount() + buyB * sb.amount();
                double revenue = sell * row.qty() * c.bazaarKeep();
                double profit = revenue - cost;
                if (profit <= c.minProfit) continue;

                double rate = Math.min(
                        Math.min(hourlyFlow(pa, sa.id(), false, now) / sa.amount(),
                                hourlyFlow(pb, sb.id(), false, now) / sb.amount()),
                        hourlyFlow(outProduct, result.id(), true, now) / row.qty());
                if (!(rate > 0)) continue;

                String resultName = shardName(result);
                String detail = shardName(sa) + " ×" + sa.amount() + " + " + shardName(sb) + " ×" + sb.amount()
                        + " → " + resultName + " ×" + row.qty() + " • check pair/order in machine";
                out.add(new Flip(resultName, detail, "", profit, profit * rate, revenue, cost,
                        weeklyInstantBuys(outProduct), rate));
            }
        }
        if (rowsSeen == 0) throw new IOException("Unexpected fusion data format");
        return out;
    }

    private static String shardName(Shard s) {
        return s.name() == null || s.name().isBlank() ? prettify(s.id()) : s.name();
    }

    private static void collectFusionRows(JsonElement e, int qty, List<FusionRow> out) {
        if (e == null) return;
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) collectFusionRows(x, qty, out);
            return;
        }
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        JsonElement inputs = o.get("inputs");
        if (inputs != null && inputs.isJsonArray() && inputs.getAsJsonArray().size() >= 2) {
            JsonArray in = inputs.getAsJsonArray();
            String a = idOf(in.get(0));
            String b = idOf(in.get(1));
            int q = qty;
            for (String k : new String[]{"outputQuantity", "output_quantity", "quantity", "outputs"}) {
                JsonElement v = o.get(k);
                if (v != null && v.isJsonPrimitive()) {
                    try {
                        q = Math.max(1, v.getAsInt());
                    } catch (Exception ignored) {
                        // keep q
                    }
                    break;
                }
            }
            if (!a.isEmpty() && !b.isEmpty()) out.add(new FusionRow(a, b, q));
            return;
        }
        for (Map.Entry<String, JsonElement> en : o.entrySet()) {
            int q = qty;
            try {
                q = Math.max(1, Integer.parseInt(en.getKey()));
            } catch (NumberFormatException ignored) {
                // key is not a quantity
            }
            collectFusionRows(en.getValue(), q, out);
        }
    }

    private static String idOf(JsonElement e) {
        if (e == null || e.isJsonNull()) return "";
        if (e.isJsonPrimitive()) return e.getAsString();
        if (e.isJsonObject()) return str(e.getAsJsonObject(), "id");
        return "";
    }

    private static JsonObject fusionData() throws Exception {
        Path file = Config.dir().resolve("fusion-data.json");
        long now = System.currentTimeMillis();
        boolean fresh = Files.exists(file) && now - Files.getLastModifiedTime(file).toMillis() < DAY;
        if (fresh) {
            try {
                return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            } catch (Exception ignored) {
                // corrupted cache: download again
            }
        }
        try {
            String text = getText(FUSION_URL);
            JsonObject parsed = JsonParser.parseString(text).getAsJsonObject();
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
            return parsed;
        } catch (Exception e) {
            if (Files.exists(file)) {
                return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            }
            throw new RuntimeException("Fusion dataset unavailable", e);
        }
    }

    // ------------------------------------------------------------------ auction crafts (Coflnet)

    private static List<Flip> computeAuction(JsonObject products, Map<String, Recipe> byId,
                                             Map<String, ItemInfo> items, Set<String> blocked,
                                             long now, Config c) throws Exception {
        JsonElement root = getJson(COFLNET_URL);
        if (!root.isJsonArray()) throw new IOException("Unexpected Coflnet response");

        List<Flip> out = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray()) {
            JsonObject o = asObject(e);
            if (o == null) continue;
            String id = str(o, "itemId");
            if (id.isEmpty()) id = str(o, "id");
            if (id.isEmpty() || products.has(id)) continue; // non-Bazaar outputs only
            Recipe r = byId.get(id);
            if (r == null) continue;

            double cost = ingredientBuyOrderCost(r.ingredients(), products, blocked, c);
            if (Double.isNaN(cost)) continue;
            double median = number(o, "median", 0);
            if (median <= 0) median = number(o, "sellPrice", 0);
            if (median <= 0) continue;

            double revenue = median * r.output() * c.auctionKeep();
            double profit = revenue - cost;
            if (profit <= c.minProfit) continue;

            double salesPerDay = number(o, "volume", 0);
            double sales = salesPerDay / 24.0 * c.marketShare;
            double rate = Math.min(craftRate(r, now, products), sales);
            if (!(rate > 0)) rate = 0;

            String name = displayName(id, r.name(), items);
            out.add(new Flip(name, "Bazaar ingredients → Auction House BIN",
                    auctionRequirements(r, items, o), profit, profit * rate, revenue, cost,
                    salesPerDay, rate));
        }
        return out;
    }

    private static String auctionRequirements(Recipe r, Map<String, ItemInfo> items, JsonObject o) {
        StringBuilder sb = new StringBuilder("Materials: ");
        boolean first = true;
        for (Ingredient in : r.ingredients()) {
            if (!first) sb.append(", ");
            sb.append(displayName(in.id(), "", items)).append(" × ").append(trimNumber(in.amount()));
            first = false;
        }
        appendRequirement(sb, "Collection", firstOf(o, "reqCollection", "requiredCollection"));
        appendRequirement(sb, "Slayer", firstOf(o, "reqSlayer", "requiredSlayer"));
        appendRequirement(sb, "Skill", firstOf(o, "reqSkill", "requiredSkill"));
        return sb.toString();
    }

    private static JsonElement firstOf(JsonObject o, String... keys) {
        for (String k : keys) {
            JsonElement e = o.get(k);
            if (e != null && !e.isJsonNull()) return e;
        }
        return null;
    }

    private static void appendRequirement(StringBuilder sb, String label, JsonElement e) {
        String text = describeRequirement(e);
        if (text.isBlank()) return;
        sb.append(" • ").append(label).append(": ").append(text);
    }

    private static String describeRequirement(JsonElement e) {
        if (e == null || e.isJsonNull()) return "";
        String text;
        if (e.isJsonPrimitive()) {
            text = e.getAsString();
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            String name = str(o, "name");
            if (name.isEmpty()) name = str(o, "type");
            if (name.isEmpty()) name = str(o, "collection");
            if (name.isEmpty()) name = str(o, "skill");
            if (name.isEmpty()) name = str(o, "slayer");
            JsonElement lv = o.get("level");
            String level = lv != null && lv.isJsonPrimitive() ? lv.getAsString() : "";
            text = (name + " " + level).trim();
            if (text.isEmpty()) text = o.toString();
        } else if (e.isJsonArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) {
                String s = describeRequirement(x);
                if (!s.isBlank()) parts.add(s);
            }
            text = String.join(", ", parts);
        } else {
            text = "";
        }
        text = text.trim();
        if (text.equals("{}") || text.equals("[]") || text.equals("0") || text.equals("false")
                || text.equals("null")) return "";
        return text;
    }

    // ------------------------------------------------------------------ pricing helpers

    private static double ingredientBuyOrderCost(List<Ingredient> ings, JsonObject products,
                                                 Set<String> blocked, Config c) {
        double total = 0;
        for (Ingredient in : ings) {
            JsonObject p = product(products, in.id());
            if (p == null) return Double.NaN;
            if (marketLooksManipulated(products, in.id(), blocked, c)) return Double.NaN;
            double price = buyOrderPrice(p);
            if (Double.isNaN(price)) return Double.NaN;
            total += price * in.amount();
        }
        return total;
    }

    /** Price you would pay by placing a buy order: top buy order + 0.1. */
    static double buyOrderPrice(JsonObject product) {
        double top = firstPrice(product, "buy_summary");
        return top > 0 ? top + 0.1 : Double.NaN;
    }

    /** Price you would list at by placing a sell offer: top sell offer - 0.1. */
    static double sellOfferPrice(JsonObject product) {
        double top = firstPrice(product, "sell_summary");
        return top > 0.1 ? top - 0.1 : Double.NaN;
    }

    private static double firstPrice(JsonObject product, String key) {
        return levelPrice(array(product, key), 0);
    }

    private static double levelPrice(JsonArray levels, int index) {
        if (levels == null || levels.size() <= index) return Double.NaN;
        JsonObject o = asObject(levels.get(index));
        return o == null ? Double.NaN : number(o, "pricePerUnit", Double.NaN);
    }

    static double weeklyInstantBuys(JsonObject product) {
        return number(object(product, "quick_status"), "buyMovingWeek", 0);
    }

    static double weeklyInstantSells(JsonObject product) {
        return number(object(product, "quick_status"), "sellMovingWeek", 0);
    }

    // ------------------------------------------------------------------ manipulation filter

    private static boolean marketLooksManipulated(JsonObject products, String id, Set<String> blocked, Config c) {
        if (!c.manipulationFilter) return false;
        if (blocked.contains(id)) return true;
        JsonObject p = product(products, id);
        if (p == null) return false;
        if (suspicious(p)) {
            blocked.add(id);
            return true;
        }
        return false;
    }

    private static boolean suspicious(JsonObject p) {
        JsonObject qs = object(p, "quick_status");
        double instantBuy = number(qs, "buyPrice", 0);
        double instantSell = number(qs, "sellPrice", 0);
        JsonArray buys = array(p, "buy_summary");
        JsonArray sells = array(p, "sell_summary");
        if (buys == null || sells == null || buys.isEmpty() || sells.isEmpty()) return true;
        if (instantSell >= 10 && instantBuy / instantSell > 2.0) return true;
        return spike(buys, true) || spike(sells, false);
    }

    /** A thin top-of-book order priced far away from the next level pulls order prices off the real market. */
    private static boolean spike(JsonArray levels, boolean buySide) {
        if (levels.size() < 2) return false;
        double top = levelPrice(levels, 0);
        double next = levelPrice(levels, 1);
        if (!(top >= 10 && next > 0)) return false;
        return buySide ? top > next * 1.4 : top < next / 1.4;
    }

    // ------------------------------------------------------------------ rate estimates

    /**
     * Estimated units per hour flowing through one side of a market.
     * instantBuys=true: players instantly buying (this is what fills your sell offers).
     * instantBuys=false: players instantly selling (this is what fills your buy orders).
     */
    private static double hourlyFlow(JsonObject product, String id, boolean instantBuys, long now) {
        double weekly = number(object(product, "quick_status"),
                instantBuys ? "buyMovingWeek" : "sellMovingWeek", 0) / WEEK_HOURS;
        double share = cfg.marketShare;
        Deque<Activity> q = (instantBuys ? BUYS : SELLS).get(id);
        long elapsed = Math.min(now - trackingSince, HOUR);
        if (q == null || elapsed < 5 * 60_000L) return weekly * share;
        double sum = 0;
        for (Activity a : q) sum += a.amount();
        double local = sum * HOUR / elapsed;
        double blended = Math.min(weekly * 2.0, 0.5 * weekly + 0.5 * local);
        return blended * share;
    }

    private static double craftRate(Recipe r, long now, JsonObject products) {
        double rate = Double.POSITIVE_INFINITY;
        for (Ingredient in : r.ingredients()) {
            JsonObject p = product(products, in.id());
            if (p == null || in.amount() <= 0) return 0;
            rate = Math.min(rate, hourlyFlow(p, in.id(), false, now) / in.amount());
        }
        return Double.isInfinite(rate) ? 0 : rate;
    }

    private static double craftBazaarRate(Recipe r, long now, JsonObject products, JsonObject outProduct) {
        double sellRate = hourlyFlow(outProduct, r.id(), true, now) / Math.max(1, r.output());
        return Math.min(craftRate(r, now, products), sellRate);
    }

    /** Remember how much the 7-day instant buy/sell counters grew since the last refresh. */
    private static void recordActivity(JsonObject products, long now) {
        for (Map.Entry<String, JsonElement> e : products.entrySet()) {
            JsonObject p = asObject(e.getValue());
            if (p == null) continue;
            JsonObject qs = object(p, "quick_status");
            if (qs == null) continue;
            double buys = number(qs, "buyMovingWeek", 0);
            double sells = number(qs, "sellMovingWeek", 0);
            double[] prev = PREVIOUS.put(e.getKey(), new double[]{buys, sells});
            if (prev == null) continue;
            addActivity(BUYS, e.getKey(), now, buys - prev[0]);
            addActivity(SELLS, e.getKey(), now, sells - prev[1]);
        }
    }

    private static void addActivity(Map<String, Deque<Activity>> map, String id, long now, double delta) {
        Deque<Activity> q = map.computeIfAbsent(id, k -> new ArrayDeque<>());
        if (delta > 0) q.addLast(new Activity(now, delta));
        while (!q.isEmpty() && now - q.peekFirst().time() > HOUR) q.pollFirst();
    }

    // ------------------------------------------------------------------ data loading

    private static String version() {
        return FabricLoader.getInstance().getModContainer("bazaarcraftflipper")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
    }

    private static String getText(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "BazaarCraftFlipper/" + version())
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        return response.body();
    }

    private static JsonElement getJson(String url) throws Exception {
        return JsonParser.parseString(getText(url));
    }

    private static Map<String, ItemInfo> items() throws Exception {
        long now = System.currentTimeMillis();
        if (itemsCache.isEmpty() || now - itemsAt > 6 * HOUR) {
            try {
                JsonArray arr = getJson(ITEMS_URL).getAsJsonObject().getAsJsonArray("items");
                Map<String, ItemInfo> map = new HashMap<>();
                for (JsonElement e : arr) {
                    JsonObject o = asObject(e);
                    if (o == null) continue;
                    String id = str(o, "id");
                    if (id.isEmpty()) continue;
                    map.put(id, new ItemInfo(str(o, "name"), number(o, "npc_sell_price", 0)));
                }
                itemsCache = map;
                itemsAt = now;
            } catch (Exception e) {
                if (itemsCache.isEmpty()) throw e;
            }
        }
        return itemsCache;
    }

    private static List<Recipe> recipes() throws Exception {
        Path dir = Config.dir();
        Files.createDirectories(dir);
        Path file = dir.resolve("recipes.json");
        Path marker = dir.resolve("recipes.version");
        String ver = version();

        boolean exists = Files.exists(file);
        boolean upToDate = exists && Files.exists(marker) && Files.readString(marker).trim().equals(ver);
        boolean keep = cfg.keepCustomRecipes && exists;
        if (!upToDate && !keep) {
            try (InputStream in = FlipData.class.getResourceAsStream("/default-recipes.json")) {
                if (in == null) throw new IOException("Bundled recipe catalog missing");
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(marker, ver);
        }

        long stamp = Files.getLastModifiedTime(file).toMillis();
        if (stamp != recipeStamp) {
            JsonArray arr = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            List<Recipe> list = new ArrayList<>();
            for (JsonElement e : arr) {
                JsonObject o = asObject(e);
                if (o == null) continue;
                String id = str(o, "id");
                JsonArray ingArr = array(o, "ingredients");
                if (id.isEmpty() || ingArr == null) continue;
                List<Ingredient> ings = new ArrayList<>();
                for (JsonElement ie : ingArr) {
                    JsonObject io = asObject(ie);
                    if (io == null) continue;
                    String iid = str(io, "id");
                    double amount = number(io, "amount", 0);
                    if (!iid.isEmpty() && amount > 0) ings.add(new Ingredient(iid, amount));
                }
                if (ings.isEmpty()) continue;
                list.add(new Recipe(id, str(o, "name"), Math.max(1, number(o, "output", 1)), List.copyOf(ings)));
            }
            recipeCache = List.copyOf(list);
            recipeStamp = stamp;
        }
        return recipeCache;
    }

    // ------------------------------------------------------------------ JSON / text helpers

    private static JsonObject product(JsonObject products, String id) {
        return asObject(products.get(id));
    }

    private static JsonObject asObject(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonObject object(JsonObject o, String key) {
        return o == null ? null : asObject(o.get(key));
    }

    private static JsonArray array(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String str(JsonObject o, String key) {
        if (o == null) return "";
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
        return e.getAsString();
    }

    private static double number(JsonObject o, String key, double def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsDouble();
        } catch (Exception ex) {
            return def;
        }
    }

    private static String displayName(String id, String fallback, Map<String, ItemInfo> items) {
        ItemInfo info = items.get(id);
        if (info != null && info.name() != null && !info.name().isBlank()) return stripColor(info.name());
        if (fallback != null && !fallback.isBlank()) return fallback;
        return prettify(id);
    }

    private static String stripColor(String s) {
        return s.replaceAll("\u00a7.", "");
    }

    private static String prettify(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private static String trimNumber(double v) {
        if (v == Math.rint(v)) return String.format(Locale.US, "%,d", (long) v);
        return String.format(Locale.US, "%s", Double.toString(Math.round(v * 100.0) / 100.0));
    }

    // ------------------------------------------------------------------ formatting (used by the screen)

    public static String compact(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "—";
        double a = Math.abs(v);
        if (a >= 1e9) return String.format(Locale.US, "%.2fB", v / 1e9);
        if (a >= 1e6) return String.format(Locale.US, "%.2fM", v / 1e6);
        if (a >= 1e3) return String.format(Locale.US, "%.1fK", v / 1e3);
        return String.format(Locale.US, "%.1f", v);
    }

    public static String coins(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "—";
        if (Math.abs(v) >= 1000) return String.format(Locale.US, "%,d", Math.round(v));
        return String.format(Locale.US, "%,.1f", v);
    }
}
