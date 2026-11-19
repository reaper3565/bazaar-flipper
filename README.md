# Bazaar Craft Flipper 1.7.0

Read-only Hypixel SkyBlock Bazaar / NPC / Auction / shard-fusion flip estimates.
It only reads public web APIs and shows numbers. It never clicks, sends packets or automates anything.

Press **B** (rebindable under Controls) to open the window.

* **1-6** switch tabs, **R** refreshes, **Esc** closes, scroll wheel or Up/Down/PageUp/PageDown scrolls.
* Type in the search box to filter. Click a flip, then type a quantity (`64`, `1.5k`, `2m`) to see batch totals.


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
