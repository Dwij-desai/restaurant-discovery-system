import java.time.LocalDate
import scala.annotation.tailrec
import org.bson.BsonDocument
import org.mongodb.scala.MongoClient
import org.mongodb.scala.bson.conversions.Bson

/** How restaurants and query details are shown on screen. */
object Views {

  def gradeBadge(grade: String): String = grade match {
    case "A"   => Ui.green(Ui.bold("A"))
    case "B"   => Ui.yellow(Ui.bold("B"))
    case "C"   => Ui.red(Ui.bold("C"))
    case ""    => Ui.dim("—")
    case other => Ui.dim(other) // P (pending), Z, Not Yet Graded
  }

  def score(value: Option[Double]): String = value.map(s => f"$s%.1f").getOrElse("—")

  def displayName(name: String): String = if (name.isBlank) Ui.dim("(no name)") else name

  /** One row per restaurant. */
  def restaurantTable(restaurants: Seq[Restaurant]): Unit =
    Ui.table(
      Seq("ID", "Name", "Cuisine", "Borough", "ZIP", "Latest", "Avg score"),
      restaurants.map { r =>
        Seq(
          r.restaurantId,
          displayName(Ui.truncate(r.name, 34)),
          Ui.truncate(r.cuisine, 24),
          r.borough,
          r.address.zipcode,
          gradeBadge(r.latestGrade.map(_.grade).getOrElse("")),
          score(r.averageScore)
        )
      },
      rightAligned = Set(6)
    )

  /** Full details of one restaurant, with its inspection history. */
  def restaurantCard(r: Restaurant): Unit = {
    val location = r.address.coord match {
      case List(longitude, latitude) => f"$latitude%.5f, $longitude%.5f"
      case _                         => "—"
    }
    val latest = r.latestGrade.map(g => gradeBadge(g.grade)).getOrElse(Ui.dim("none yet"))
    Ui.card(
      if (r.name.isBlank) "(no name)" else r.name,
      Seq(
        "Restaurant ID" -> Ui.bold(r.restaurantId),
        "Cuisine"       -> r.cuisine,
        "Borough"       -> r.borough,
        "Address"       -> r.address.oneLine,
        "Coordinates"   -> location,
        "Inspections"   -> s"${r.grades.size}   ·   average score ${score(r.averageScore)}   ·   latest grade $latest"
      )
    )
    if (r.grades.nonEmpty) {
      // groupBy: how many times each grade was given to this restaurant
      val gradeCounts = r.grades.groupBy(_.grade).map((grade, list) => (grade, list.size)).toSeq.sortBy(-_._2)
      Ui.note("  Grade summary: " + gradeCounts.map((g, n) => s"$g × $n").mkString("   "))
      val newestFirst = r.grades.sortBy(_.date.map(_.toEpochDay)).reverse
      Ui.table(
        Seq("Inspection date", "Grade", "Score"),
        newestFirst.map(g => Seq(g.date.map(_.toString).getOrElse("—"), gradeBadge(g.grade), g.score.map(_.toString).getOrElse("—"))),
        rightAligned = Set(2)
      )
    }
  }

  /** Shows an aggregation pipeline the way you would write it in mongosh, one stage per line. */
  def pipeline(stages: Seq[Bson]): Unit = {
    Ui.note("MongoDB pipeline:")
    stages.foreach { stage =>
      val json = stage.toBsonDocument(classOf[BsonDocument], MongoClient.DEFAULT_CODEC_REGISTRY).toJson
      // long stages are wrapped onto extra lines instead of being cut off
      json.grouped(104).zipWithIndex.foreach((part, i) => Ui.note((if (i == 0) "  " else "    ") + part))
    }
  }

  def timing(millis: Long): String = Ui.dim(s"$millis ms")

  // ---------- prompts shared by several screens ----------

  @tailrec
  def askZip(prompt: String): String = {
    val zip = Ui.ask(prompt)
    if (Restaurant.isValidZip(zip)) zip
    else {
      Ui.error("A ZIP code is exactly 5 digits, for example 10019.")
      askZip(prompt)
    }
  }

  def askBorough(): String = Ui.choose("Borough (1-5):", Restaurant.Boroughs)(identity)

  /** Asks for a cuisine and matches it against the cuisines in the dataset. */
  def askCuisine(known: Seq[String], allowNew: Boolean): Option[String] = {
    val typed = Ui.askNonEmpty("Cuisine (full or part of the name, e.g. ital):")
    val exact = known.find(_.equalsIgnoreCase(typed))
    val partial = known.filter(_.toLowerCase.contains(typed.toLowerCase))
    (exact, partial) match {
      case (Some(cuisine), _) => Some(cuisine)
      case (None, Seq(only)) =>
        Ui.info(s"Using ${Ui.bold(only)}")
        Some(only)
      case (None, many) if many.nonEmpty =>
        Some(Ui.choose("Which cuisine?", many.take(20))(identity))
      case _ if allowNew && Ui.confirm(s"\"$typed\" is not in the dataset yet. Add it as a new cuisine?") =>
        Some(typed)
      case _ =>
        Ui.warn(s"No cuisine matches \"$typed\".")
        None
    }
  }

  /** Asks for an inspection score and works out the grade from it. */
  def askInspection(): Grade = {
    val score = Ui.askInt("Inspection score (0-100, lower is better):", 0, 100)
    val grade = Restaurant.gradeFor(score)
    Ui.info(s"Score $score earns grade ${gradeBadge(grade)}")
    Grade(Some(LocalDate.now()), grade, Some(score))
  }
}
