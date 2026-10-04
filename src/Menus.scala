import scala.util.Try
import scala.util.control.NonFatal
import com.mongodb.MongoException

/** One menu option: the key to type, its label, a short hint, and what it does. */
case class MenuItem(key: String, label: String, hint: String, action: () => Unit, opensSubMenu: Boolean = false)

/**
 * A screen with numbered options. Each menu extends this trait and only lists its items;
 * drawing the menu, reading the choice and catching errors is shared here (inheritance).
 */
trait MenuScreen {
  def title: String
  def items: Seq[MenuItem]
  def exitKey: String = "0"
  def exitLabel: String = "Back"
  def statusLines: Seq[String] = Nil

  def run(): Unit = {
    var running = true
    var notice: Option[String] = None
    while (running) {
      Ui.clear()
      Ui.banner()
      statusLines.foreach(line => println("  " + line))
      Ui.header(title)
      Ui.menu(items.map(i => (i.key, Ui.padRight(i.label, 34) + Ui.dim(i.hint))) :+ (exitKey, exitLabel))
      notice.foreach { message => println(); Ui.error(message) }
      notice = None
      println()
      val choice = Ui.ask("Choose an option:")
      if (choice == exitKey) running = false
      else
        items.find(_.key == choice) match {
          case Some(item) if item.opensSubMenu => MenuScreen.safely(item.action())
          case Some(item) =>
            Ui.clear()
            Ui.breadcrumb(title)
            MenuScreen.safely(item.action())
            Ui.pause()
          case None => notice = Some(s"\"$choice\" is not an option. Type one of the numbers shown.")
        }
    }
  }
}

object MenuScreen {

  /** Runs an action; database or other errors become a message instead of crashing the app. */
  def safely(action: => Unit): Unit =
    try action
    catch {
      case e: InputClosedException => throw e // let main() end the program
      case e: MongoException       => Ui.error(s"Database error: ${e.getMessage}")
      case NonFatal(e)             => Ui.error(s"Something went wrong: ${e.getMessage}")
    }
}

// =====================================================================================
//  Main menu  (composition: it is built from the other parts of the app)
// =====================================================================================

class MainMenu(db: Database, repo: RestaurantRepository, crud: CrudActions, analytics: AnalyticsService, indexes: IndexManager)
    extends MenuScreen {

  private val searchMenu = SearchMenu(repo, crud)
  private val analyticsMenu = AnalyticsMenu(analytics)
  private val indexMenu = IndexMenu(indexes)

  val title = "Main Menu"
  override val exitKey = "7"
  override val exitLabel = "Exit"

  val items: Seq[MenuItem] = Seq(
    MenuItem("1", "Add Restaurant", "create a new record", () => crud.addRestaurant()),
    MenuItem("2", "Search / View Restaurants", "7 ways to find restaurants", () => searchMenu.run(), opensSubMenu = true),
    MenuItem("3", "Update Restaurant", "change details or add an inspection", () => crud.updateRestaurant()),
    MenuItem("4", "Delete Restaurant", "remove a record", () => crud.deleteRestaurant()),
    MenuItem("5", "Restaurant Analytics", "6 aggregation reports", () => analyticsMenu.run(), opensSubMenu = true),
    MenuItem("6", "Index Information", "list, create and explain indexes", () => indexMenu.run(), opensSubMenu = true)
  )

  override def statusLines: Seq[String] =
    Try {
      val total = repo.count()
      val existing = indexes.list().map(_.name).toSet
      val ready = indexes.plans.count(p => existing.contains(p.name))
      val indexText =
        if (ready == indexes.plans.size) Ui.green(s"$ready/$ready app indexes ready")
        else Ui.yellow(s"$ready/${indexes.plans.size} app indexes — create them in 6 › Index Information")
      Seq(
        s"${Ui.green("●")} Connected to ${Ui.bold(db.host)}",
        s"  ${Config.DatabaseName}.${Config.CollectionName}  ${Ui.dim("·")}  ${Ui.bold(Ui.number(total))} restaurants  ${Ui.dim("·")}  $indexText"
      )
    }.getOrElse(Seq(Ui.yellow("● Connection status unavailable")))
}

// =====================================================================================
//  2. Search / View
// =====================================================================================

class SearchMenu(repo: RestaurantRepository, crud: CrudActions) extends MenuScreen {
  private val PageSize = 15

  val title = "Search / View Restaurants"

  val items: Seq[MenuItem] = Seq(
    MenuItem("1", "Search by name", "text anywhere in the name", () => byName()),
    MenuItem("2", "Search by cuisine", "e.g. Italian, Chinese", () => byCuisine()),
    MenuItem("3", "Search by borough", "Manhattan, Brooklyn, ...", () => show(ByBorough(Views.askBorough()))),
    MenuItem("4", "Search by ZIP code", "e.g. 10019", () => show(ByZipCode(Views.askZip("ZIP code (5 digits):")))),
    MenuItem("5", "Search by inspection score", "a score range, e.g. 0 to 2", () => byScore()),
    MenuItem("6", "Search by cuisine + borough", "two filters together", () => byCuisineAndBorough()),
    MenuItem("7", "View one restaurant", "by ID or name, with full history", () => crud.viewRestaurant()),
    MenuItem("8", "Restaurants added with this app", "your new records", () => show(AddedByThisApp))
  )

  private def byName(): Unit = {
    Ui.header("Search by name")
    show(ByName(Ui.askNonEmpty("Name contains:")))
  }

  private def byCuisine(): Unit = {
    Ui.header("Search by cuisine")
    Views.askCuisine(repo.cuisines, allowNew = false).foreach(c => show(ByCuisine(c)))
  }

  private def byScore(): Unit = {
    Ui.header("Search by inspection score")
    Ui.note("Finds restaurants with at least one inspection scored in this range. Lower is better.")
    val first = Ui.askInt("Lowest score:", 0, 200)
    val second = Ui.askInt("Highest score:", 0, 200)
    // Accept the numbers in either order
    val (low, high) = if (first <= second) (first, second) else (second, first)
    show(ByScoreRange(low, high))
  }

  private def byCuisineAndBorough(): Unit = {
    Ui.header("Search by cuisine + borough")
    Views.askCuisine(repo.cuisines, allowNew = false).foreach { cuisine =>
      show(ByCuisineAndBorough(cuisine, Views.askBorough()))
    }
  }

  /** Runs a search and prints the first page of results with a short summary. */
  private def show(search: SearchFilter): Unit = {
    val result = repo.search(search, PageSize)
    println()
    Ui.info(s"Restaurants where ${Ui.bold(search.description)}")
    if (result.restaurants.isEmpty) Ui.warn("No restaurants found.")
    else {
      Views.restaurantTable(result.restaurants.sortBy(_.name.toLowerCase))
      val shown = result.restaurants.size
      Ui.note(s"  Showing $shown of ${Ui.number(result.totalMatches)} matches  ·  ${result.millis} ms  ·  index: ${SearchFilter.expectedIndex(search)}")
      // groupBy on the page we got back: how many results per borough
      val perBorough = result.restaurants.groupBy(_.borough).map((borough, list) => s"$borough ${list.size}")
      Ui.note(s"  On this page by borough: ${perBorough.mkString("  ·  ")}")
    }
  }
}

// =====================================================================================
//  5. Analytics
// =====================================================================================

class AnalyticsMenu(analytics: AnalyticsService) extends MenuScreen {

  val title = "Restaurant Analytics"

  val items: Seq[MenuItem] = Seq(
    MenuItem("1", "Restaurants by cuisine", "top 10 cuisines", () => byCuisine()),
    MenuItem("2", "Restaurants by borough", "count and share", () => byBorough()),
    MenuItem("3", "Average score by cuisine", "cleanest or worst cuisines", () => averageScore()),
    MenuItem("4", "Top-rated restaurants", "lowest average score", () => topRated()),
    MenuItem("5", "Grade distribution", "A / B / C across all inspections", () => grades()),
    MenuItem("6", "Most popular cuisine per borough", "two $group stages", () => favourites())
  )

  private def percent(part: Long, whole: Long): String = f"${part * 100.0 / whole.max(1)}%.1f%%"

  private def footer(report: Report[?]): Unit = {
    println()
    Views.pipeline(report.pipeline)
    Ui.note(s"Aggregation ran in ${report.millis} ms")
  }

  private def byCuisine(): Unit = {
    Ui.header("Restaurants by cuisine  —  top 10")
    val report = analytics.restaurantsByCuisine(top = 10)
    Ui.barChart(report.rows.map(r => (Ui.truncate(r.label, 30), r.count.toDouble)), v => Ui.number(v.toLong))
    footer(report)
  }

  private def byBorough(): Unit = {
    Ui.header("Restaurants by borough")
    val report = analytics.restaurantsByBorough()
    val total = report.rows.map(_.count).sum
    Ui.barChart(report.rows.map(r => (r.label, r.count.toDouble)), v => s"${Ui.number(v.toLong)}  ${Ui.dim(percent(v.toLong, total))}")
    Ui.note(s"  Total: ${Ui.number(total)} restaurants")
    footer(report)
  }

  private def averageScore(): Unit = {
    Ui.header("Average inspection score by cuisine")
    val cleanestFirst = Ui.choose("Show which end?", Seq(true, false)) {
      case true  => "Cleanest cuisines (lowest average score)"
      case false => "Cuisines with the most violations (highest average score)"
    }
    val report = analytics.averageScoreByCuisine(minInspections = 100, top = 10, cleanestFirst)
    println()
    val highest = report.rows.map(_.averageScore).maxOption.getOrElse(1.0)
    Ui.table(
      Seq("#", "Cuisine", "Avg score", "", "Inspections"),
      report.rows.zipWithIndex.map { case (row, i) =>
        Seq((i + 1).toString, Ui.truncate(row.cuisine, 34), f"${row.averageScore}%.2f",
          Ui.teal(Ui.bar(row.averageScore / highest * 20)), Ui.number(row.inspections))
      },
      rightAligned = Set(0, 2, 4)
    )
    Ui.note("  Lower score = fewer violations. Only cuisines with 100+ scored inspections.")
    footer(report)
  }

  private def topRated(): Unit = {
    Ui.header("Top-rated restaurants")
    val area = Ui.choose("Which area?", "All boroughs" +: Restaurant.Boroughs)(identity)
    val borough = area match {
      case "All boroughs" => None
      case name           => Some(name)
    }
    val report = analytics.topRatedRestaurants(borough, minInspections = 4, top = 10)
    println()
    Ui.table(
      Seq("#", "ID", "Name", "Cuisine", "Borough", "Avg score", "Inspections"),
      report.rows.zipWithIndex.map { case (r, i) =>
        Seq((i + 1).toString, r.id, Ui.truncate(r.name, 32), Ui.truncate(r.cuisine, 20), r.borough, f"${r.averageScore}%.2f", r.inspections.toString)
      },
      rightAligned = Set(0, 5, 6)
    )
    Ui.note("  Ranked by lowest average inspection score (4+ inspections).")
    footer(report)
  }

  private def grades(): Unit = {
    Ui.header("Grade distribution across all inspections")
    val report = analytics.gradeDistribution()
    val total = report.rows.map(_.count).sum
    val labelled = report.rows.map { r =>
      val label = r.label match {
        case "A" | "B" | "C"    => s"Grade ${r.label}"
        case "P" | "Z"          => s"${r.label}  (pending)"
        case "Not Yet Graded"   => "Not yet graded"
        case other              => other
      }
      (label, r.count.toDouble)
    }
    Ui.barChart(labelled, v => s"${Ui.number(v.toLong)}  ${Ui.dim(percent(v.toLong, total))}")
    Ui.note(s"  Total inspections: ${Ui.number(total)}")
    footer(report)
  }

  private def favourites(): Unit = {
    Ui.header("Most popular cuisine in each borough")
    val report = analytics.favouriteCuisinePerBorough()
    Ui.table(
      Seq("Borough", "Most common cuisine", "Restaurants", "Share of borough"),
      report.rows.map(r => Seq(r.borough, r.cuisine, Ui.number(r.count), percent(r.count, r.boroughTotal))),
      rightAligned = Set(2, 3)
    )
    footer(report)
  }
}

// =====================================================================================
//  6. Indexes
// =====================================================================================

class IndexMenu(indexes: IndexManager) extends MenuScreen {

  val title = "Index Information"

  val items: Seq[MenuItem] = Seq(
    MenuItem("1", "Show indexes", "what exists and what uses it", () => showIndexes()),
    MenuItem("2", "Create the app's indexes", "6 indexes, 2 of them compound", () => createIndexes()),
    MenuItem("3", "Explain a search", "index scan vs full collection scan", () => explain()),
    MenuItem("4", "Drop the app's indexes", "to start the demo again", () => dropIndexes())
  )

  private def showIndexes(): Unit = {
    Ui.header("Indexes on sample_restaurants.restaurants")
    val existing = indexes.list()
    val usage = indexes.usageCounts()
    Ui.table(
      Seq("Name", "Keys", "Type", "Used by", "Times used"),
      existing.map { info =>
        val plan = indexes.plans.find(_.name == info.name)
        val kind = plan.map(_.kind).getOrElse(if (info.fieldCount > 1) "compound" else "single")
        val usedBy = plan.map(_.usedBy).getOrElse(if (info.name == "_id_") "MongoDB default" else "—")
        Seq(Ui.bold(info.name), info.keysText, if (kind == "compound") Ui.accent(kind) else kind, usedBy,
          usage.get(info.name).map(Ui.number).getOrElse("n/a"))
      },
      rightAligned = Set(4)
    )
    val compound = existing.count(_.fieldCount > 1)
    Ui.note(s"  ${existing.size} indexes in total, $compound compound.  \"Times used\" counts since the server last restarted ($$indexStats).")
  }

  private def createIndexes(): Unit = {
    Ui.header("Create the app's indexes")
    val (results, millis) = Ui.timed(indexes.createMissing())
    Ui.table(
      Seq("Name", "Keys", "Type", "Result"),
      results.map { (plan, created) =>
        Seq(Ui.bold(plan.name), plan.keysText, plan.kind, if (created) Ui.green("created ✔") else Ui.dim("already existed"))
      }
    )
    Ui.note(s"  createIndex() finished in $millis ms. Running it again is safe: existing indexes are kept.")
  }

  private def dropIndexes(): Unit = {
    Ui.header("Drop the app's indexes")
    if (Ui.confirm("Drop the 6 app indexes? (the _id index always stays)")) {
      val dropped = indexes.dropAppIndexes()
      Ui.success(s"Dropped $dropped indexes. Use option 2 to create them again.")
    } else Ui.warn("Nothing dropped.")
  }

  /** Example searches to explain, one for each index. */
  private val examples: Seq[SearchFilter] = Seq(
    ByCuisine("Chinese"),
    ByCuisineAndBorough("Italian", "Manhattan"),
    ByBorough("Staten Island"),
    ByZipCode("10019"),
    ByScoreRange(0, 1),
    ByName("pizza")
  )

  private def explain(): Unit = {
    Ui.header("Explain a search  —  with index vs. without")
    val search = Ui.choose("Which search?", examples)(s => s"${s.description}  ${Ui.dim("→ " + SearchFilter.expectedIndex(s))}")
    val withIndex = indexes.explain(search, forceCollectionScan = false)
    val withoutIndex = indexes.explain(search, forceCollectionScan = true)

    def column(plan: QueryPlan): Seq[String] = Seq(
      plan.stages.mkString(" → "),
      plan.indexName.getOrElse("—"),
      Ui.number(plan.returned),
      Ui.number(plan.keysExamined),
      Ui.number(plan.docsExamined),
      s"${plan.millis} ms"
    )
    val labels = Seq("Plan stages", "Index used", "Documents returned", "Index keys examined", "Documents examined", "Time on server")
    println()
    Ui.info(s"db.restaurants.find(${Ui.bold(search.description)}).explain(\"executionStats\")")
    Ui.table(
      Seq("", "With index", "Without index (forced)"),
      labels.zip(column(withIndex)).zip(column(withoutIndex)).map { case ((label, a), b) => Seq(Ui.dim(label), Ui.green(a), Ui.yellow(b)) }
    )
    if (withIndex.usedIndex && withIndex.docsExamined < withoutIndex.docsExamined) {
      val times = withoutIndex.docsExamined.toDouble / withIndex.docsExamined.max(1)
      Ui.success(f"The index let MongoDB read ${Ui.number(withIndex.docsExamined)} documents instead of ${Ui.number(withoutIndex.docsExamined)} ($times%.1fx fewer).")
    } else if (!withIndex.usedIndex)
      Ui.warn("No index was used. Create the app's indexes first (option 2).")
  }
}
