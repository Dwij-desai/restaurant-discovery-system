# 🍽️ Restaurant Discovery & Analytics System

A menu-driven **Scala 3 CLI** that searches, edits and analyses New York restaurants stored in **MongoDB Atlas**, using MongoDB's official `sample_restaurants.restaurants` dataset (25,359 restaurants).

> **ABDA assignment** · Dwij Desai · 23162121027 · B.Tech CSE, Sem 7 · Class A, Batch 64

![Main menu](screenshots/05-main-menu-indexes-ready.png)

---

## What it does

| Requirement | In this app |
|---|---|
| **Create** (2+ records) | Menu 1. Guided form with validation (5-digit ZIP, borough list, cuisine matched to the dataset). The ID is generated and the score is turned into a grade automatically (NYC rule: 0–13 A, 14–27 B, 28+ C). |
| **Read** | Menu 2. Seven searches plus a full detail view (inspection history and grade summary). |
| **Update** (1+ existing) | Menu 3. Change name, cuisine, borough or address, or `$push` a new inspection. Before and after are both shown. |
| **Delete** (1+) | Menu 4. Asks for confirmation, shows the count before and after, then looks the ID up again to prove it's gone. |
| **Search / filter** (3+) | Name (contains), cuisine, borough, ZIP code, inspection score range, cuisine + borough, records added with this app |
| **Indexes** (2+, 1 compound) | 6 indexes, **2 compound**, each tied to a query. Menu 6 also runs `explain()` with the index and with a forced collection scan, side by side. |
| **Aggregation** (3+) | 6 pipelines: by cuisine, by borough, average score by cuisine, top-rated restaurants, grade distribution, most popular cuisine per borough |
| **Menu** | The 7 items from the brief: Add · Search/View · Update · Delete · Analytics · Index Information · Exit |

## Setup

**You need:** Java 17 or newer, [Scala CLI](https://scala-cli.virtuslab.org/install) (`brew install Virtuslab/scala-cli/scala-cli`), and a MongoDB Atlas cluster with the sample dataset loaded (Atlas → your cluster → **⋯** → **Load Sample Dataset**). sbt is not needed.

```bash
git clone <this repository>
cd restaurant-discovery
cp .env.example .env          # then paste your Atlas connection string into .env
scala-cli run .
```

`.env` holds a single line, `MONGODB_URI=mongodb+srv://<user>:<password>@<cluster>.mongodb.net/`. It is git-ignored, so the password is never committed. Setting the `MONGODB_URI` environment variable also works. The first run downloads the MongoDB Scala driver automatically.

**Suggested first steps:** 6 › 2 to create the indexes, then try the searches, then CRUD, then analytics.

If Atlas can't be reached, the app says why in plain words: wrong password, IP not on the Network Access list, or the sample dataset not loaded.

## Project structure

```
restaurant-discovery/
├── project.scala        Scala CLI build: Scala 3.8, mongo-scala-driver 5.13
├── .env.example         template for the connection string
└── src/
    ├── Main.scala       entry point: connect, explain connection errors, start the menu
    ├── Database.scala   Config (.env reader), Sync helpers, Database (owns the MongoClient)
    ├── Models.scala     Restaurant / Address / Grade case classes + BSON conversion
    ├── Repository.scala Repository[T, ID] trait + RestaurantRepository (all CRUD)
    ├── Search.scala     SearchFilter sealed trait → MongoDB filters
    ├── Indexes.scala    IndexManager: create, list, $indexStats, explain()
    ├── Analytics.scala  AnalyticsService: the 6 aggregation pipelines
    ├── Menus.scala      MenuScreen trait + Main / Search / Analytics / Index menus
    ├── CrudActions.scala Add / View / Update / Delete screens
    ├── Views.scala      restaurant table, detail card, shared prompts
    └── Ui.scala         colours, boxed tables, bar charts, input helpers
```

## Indexes

| Index | Type | Used by |
|---|---|---|
| `restaurant_id_1` | single | View / update / delete by ID |
| `name_1` | single | Search by name (results in A–Z order) |
| `cuisine_1_borough_1` | **compound** | Search by cuisine; cuisine + borough |
| `borough_1_name_1` | **compound** | Search by borough; top-rated in one borough (`$match` first) |
| `address.zipcode_1` | single | Search by ZIP code |
| `grades.score_1` | multikey | Search by inspection score range (`$elemMatch`) |

Example from the app: for `cuisine = "Chinese"`, the index meant MongoDB examined **2,418** documents instead of **25,360** (10.5× fewer). For ZIP 10019 it examined 675 instead of 25,360 (37.6× fewer).

## Aggregations

| # | Report | Pipeline |
|---|---|---|
| 1 | Restaurants by cuisine (top 10) | `$group` → `$sort` → `$limit` |
| 2 | Restaurants by borough + share | `$group` → `$sort` |
| 3 | Average inspection score by cuisine | `$unwind` → `$match` → `$group` (`$avg`) → `$match` → `$sort` → `$limit` |
| 4 | Top-rated restaurants (optional borough) | `$match`? → `$project` (`$avg`, `$size`) → `$match` → `$sort` → `$limit` |
| 5 | Grade distribution | `$unwind` → `$group` → `$sort` |
| 6 | Most popular cuisine per borough | `$match` → `$group` → `$sort` → `$group` (`$first`) → `$sort` |

Each report prints the exact pipeline it ran, so you can paste it into `mongosh`.

## Scala concepts used

| Concept | Where |
|---|---|
| Variables, data types, conditions, functions | `Restaurant.gradeFor` (if/else), `Models.scala:99`; score-range swap, `Menus.scala:134` |
| Collections: `map`, `filter`, `groupBy`, `sortBy`, `zipWithIndex` | `Views.scala:60`, `Menus.scala:156`, `Database.scala:22` |
| `Option` | `Restaurant.averageScore`, `Models.scala:30`; `Config.mongoUri`, `Database.scala:15` |
| Pattern matching | `SearchFilter.toFilter`, `Search.scala:24`; BSON type matching, `Models.scala:126`; explain plan walker, `Indexes.scala:90` |
| Exception handling | `MenuScreen.safely`, `Menus.scala:50`; connection errors (`Try` / `Failure`), `Main.scala:24`; `Using` for the file, `Database.scala:22` |
| Case classes, objects, methods | `Restaurant`, `Models.scala:17`; `object RestaurantApp`, `Ui`, `Config` |
| Encapsulation | `private val client`, `Database.scala:51`; private helpers in `RestaurantRepository` |
| Trait + inheritance | `trait Repository[T, ID]`, `Repository.scala:8`, implemented by `RestaurantRepository`; `trait MenuScreen`, `Menus.scala:12`, extended by 4 menus (`MainMenu` overrides `exitKey`) |
| Composition | `MainMenu`, `Menus.scala:63`, is built from the repository, CRUD actions, analytics and index manager |
| Sealed trait (ADT) | `SearchFilter`, `Search.scala:9` |
| Generics / higher-order functions | `Ui.choose[T]`, `Ui.scala:176`; `AnalyticsService.run[T]` |
| Tail recursion | `Ui.askInt`, `Ui.scala:164` (`@tailrec`) |

## Screenshots

All screenshots come from one real session against MongoDB Atlas (`screenshots/` folder).

### Connection and menu
![Startup](screenshots/01-startup-connected.png)
![Main menu before indexes](screenshots/02-main-menu-before-indexes.png)

### Indexes
| Before | Created |
|---|---|
| ![](screenshots/03-indexes-before.png) | ![](screenshots/04-indexes-created.png) |

![Indexes after use](screenshots/27-indexes-after-use.png)
![Explain cuisine](screenshots/28-explain-cuisine-index.png)
![Explain ZIP](screenshots/29-explain-zipcode-index.png)

### Search / filter
![Search menu](screenshots/06-search-menu.png)
![By name](screenshots/07-search-by-name.png)
![By cuisine](screenshots/08-search-by-cuisine.png)
![By borough](screenshots/09-search-by-borough.png)
![By ZIP](screenshots/10-search-by-zip.png)
![By score](screenshots/11-search-by-score-range.png)
![Cuisine + borough](screenshots/12-search-cuisine-and-borough.png)
![View one](screenshots/13-view-restaurant.png)

### CRUD
![Create 1](screenshots/14-create-restaurant-1.png)
![Create 2](screenshots/15-create-restaurant-2-with-validation.png)
![Read added](screenshots/16-read-added-restaurants.png)
![Update existing](screenshots/17-update-existing-restaurant.png)
![Update added](screenshots/18-update-added-restaurant.png)
![Delete](screenshots/19-delete-restaurant.png)

### Aggregations
![Analytics menu](screenshots/20-analytics-menu.png)
![By cuisine](screenshots/21-agg-restaurants-by-cuisine.png)
![By borough](screenshots/22-agg-restaurants-by-borough.png)
![Average score](screenshots/23-agg-average-score-by-cuisine.png)
![Top rated](screenshots/24-agg-top-rated-brooklyn.png)
![Grades](screenshots/25-agg-grade-distribution.png)
![Per borough](screenshots/26-agg-top-cuisine-per-borough.png)

### Exit
![Final menu](screenshots/30-main-menu-final.png)
![Exit](screenshots/31-exit.png)
