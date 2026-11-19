package dev.codex.bazaarflip;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User-editable settings, stored in config/bazaarcraftflipper/config.json.
 * The file is re-read on every refresh, so after editing it just press R in the flip screen.
 */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Bazaar sell-offer tax in percent. Base rate is 1.25; lower it if you have tax-reducing upgrades. */
    public double bazaarTaxPercent = 1.25;

    /** Auction House cut applied to the expected sale price, in percent (listing + claim fees, rough). */
    public double auctionTaxPercent = 2.0;

    /** Seconds between automatic price refreshes. Minimum 15. */
    public int refreshSeconds = 35;

    /** Flips whose market sees fewer than this many instant buys / 7 days are hidden (Bazaar tabs). */
    public double minWeeklyVolume = 1000;

    /** Hide flips that earn less than this many coins per unit / craft. */
    public double minProfit = 0.0;

    /** Hide markets that look manipulated (huge spreads, one-unit price walls). */
    public boolean manipulationFilter = true;

    /** Share of the market flow you expect to capture (1.0 = all of it, 0.25 = a quarter). Scales every coins/hr estimate. */
    public double marketShare = 1.0;

    /** If true, a recipes.json you edited in the config folder is never overwritten by the bundled one. */
    public boolean keepCustomRecipes = false;

    /** Maximum number of rows kept per tab. */
    public int maxRows = 400;

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("bazaarcraftflipper");
    }

    public static Config load() {
        Path file = dir().resolve("config.json");
        Config cfg = null;
        try {
            if (Files.exists(file)) {
                cfg = GSON.fromJson(Files.readString(file), Config.class);
            }
        } catch (Exception ignored) {
            // fall through to defaults
        }
        if (cfg == null) {
            cfg = new Config();
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(cfg));
            } catch (IOException ignored) {
                // read-only config dir: just use defaults
            }
        }
        cfg.sanitize();
        return cfg;
    }

    private void sanitize() {
        if (!(bazaarTaxPercent >= 0 && bazaarTaxPercent < 100)) bazaarTaxPercent = 1.25;
        if (!(auctionTaxPercent >= 0 && auctionTaxPercent < 100)) auctionTaxPercent = 2.0;
        if (refreshSeconds < 15) refreshSeconds = 15;
        if (!(marketShare > 0)) marketShare = 1.0;
        if (maxRows < 20) maxRows = 20;
        if (minWeeklyVolume < 0) minWeeklyVolume = 0;
    }

    public double bazaarKeep() {
        return 1.0 - bazaarTaxPercent / 100.0;
    }

    public double auctionKeep() {
        return 1.0 - auctionTaxPercent / 100.0;
    }
}
