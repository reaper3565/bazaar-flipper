# Bazaar Craft Flipper 1.7.0

Read-only Hypixel SkyBlock Bazaar / NPC / Auction / shard-fusion flip estimates.
It only reads public web APIs and shows numbers. It never clicks, sends packets or automates anything.

Press **B** (rebindable under Controls) to open the window.

* **1-6** switch tabs, **R** refreshes, **Esc** closes, scroll wheel or Up/Down/PageUp/PageDown scrolls.
* Type in the search box to filter. Click a flip, then type a quantity (`64`, `1.5k`, `2m`) to see batch totals.

## Build it without installing anything (GitHub)

1. Make a free account at github.com and create a new **empty** repository.
2. Click **Add file -> Upload files**, drag in everything from this folder except `.github`, and commit.
3. Click **Add file -> Create new file**, name it exactly `.github/workflows/build.yml`
   (typing the slashes creates the folders), paste in the contents of `build.yml`, and commit.
4. Open the **Actions** tab. A run called "build" starts by itself (about 3-5 minutes).
5. When it has a green tick, open it and download the **BazaarCraftFlipper** artifact. Unzip it to get the `.jar`.
6. Put the jar in your `.minecraft/mods` folder next to Fabric API (Minecraft 26.2, Fabric Loader 0.20.1+, Java 25).

### If the build fails

Open the failed run, click the red step, and copy the error text. Two likely causes:

* **"Could not resolve ..."** - the Fabric version numbers in `gradle.properties` are wrong for 26.2.
  Go to https://fabricmc.net/develop , choose 26.2, and copy `loader_version`, `loom_version` and
  `fabric_api_version` into `gradle.properties`.
* **"cannot find symbol"** in a Java file - a Minecraft method got renamed. Send the error to Claude.

## Settings

`.minecraft/config/bazaarcraftflipper/config.json` is created on first launch. Edit it, then press R in the window.

| Setting | Default | Meaning |
|---|---|---|
| bazaarTaxPercent | 1.25 | Bazaar sell tax used for after-tax numbers |
| auctionTaxPercent | 2.0 | Rough Auction House cut |
| refreshSeconds | 35 | Automatic refresh interval (minimum 15) |
| minWeeklyVolume | 1000 | Hide Bazaar flips with fewer instant buys per 7 days |
| minProfit | 0 | Hide flips earning less than this per unit |
| manipulationFilter | true | Hide markets with huge spreads or thin price walls |
| marketShare | 1.0 | Fraction of market flow you expect to get. Lower it (0.25) for cautious coins/hr |
| keepCustomRecipes | false | Never overwrite an edited `recipes.json` |
| maxRows | 400 | Rows kept per tab |

## Data sources

* Hypixel public API: Bazaar prices and item list (NPC sell prices)
* Coflnet `/api/craft/profit`: Auction House median price and requirements
* Campionnn/SkyShards `fusion-data.json`: shard fusion recipes (cached for 24 h)
* Bundled `default-recipes.json` derived from the NotEnoughUpdates repo (MIT, see `META-INF/licenses`)

## Notes on the estimates

* "Coins/hr" is profit per unit x how many units the market can move per hour, assuming you capture
  `marketShare` of it. It is an upper-bound style estimate, not a promise.
* Hourly rates blend the 7-day average with activity this mod saw since you started the game.
