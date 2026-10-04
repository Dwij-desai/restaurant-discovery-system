import org.bson.{BsonArray, BsonDocument, BsonString}
import org.mongodb.scala.*
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Accumulators.*
import org.mongodb.scala.model.Aggregates.*
import org.mongodb.scala.model.Filters.{and, equal, gte, notEqual}
import org.mongodb.scala.model.Projections.{computed, excludeId, fields, include}
import org.mongodb.scala.model.Sorts.{ascending, descending, orderBy}

/** The rows an aggregation produced, the pipeline that produced them, and how long it took. */
case class Report[T](rows: Seq[T], pipeline: Seq[Bson], millis: Long)

case class CountRow(label: String, count: Long)
case class ScoreRow(cuisine: String, averageScore: Double, inspections: Long)
case class RatedRestaurant(id: String, name: String, cuisine: String, borough: String, averageScore: Double, inspections: Long)
case class BoroughFavourite(borough: String, cuisine: String, count: Long, boroughTotal: Long)

/** MongoDB aggregation pipelines. All the grouping and averaging happens inside the database. */
class AnalyticsService(collection: MongoCollection[Document]) {
  import Sync.*
  import BsonFields.{number, text}

  /** Runs a pipeline and turns each result document into a row with the given function. */
  private def run[T](pipeline: Seq[Bson])(toRow: BsonDocument => T): Report[T] = {
    val (docs, millis) = Ui.timed(collection.aggregate(pipeline).results())
    Report(docs.map(d => toRow(d.toBsonDocument)), pipeline, millis)
  }

  /** 1. How many restaurants serve each cuisine. */
  def restaurantsByCuisine(top: Int): Report[CountRow] =
    run(Seq(
      group("$cuisine", sum("count", 1)),
      sort(descending("count")),
      limit(top)
    ))(d => CountRow(text(d, "_id"), number(d, "count").toLong))

  /** 2. How many restaurants are in each borough. */
  def restaurantsByBorough(): Report[CountRow] =
    run(Seq(
      group("$borough", sum("count", 1)),
      sort(descending("count"))
    ))(d => CountRow(text(d, "_id"), number(d, "count").toLong))

  /** 3. Average inspection score per cuisine (lower is cleaner). Small cuisines are left out. */
  def averageScoreByCuisine(minInspections: Int, top: Int, cleanestFirst: Boolean): Report[ScoreRow] =
    run(Seq(
      unwind("$grades"),
      filter(gte("grades.score", 0)), // skips inspections with no score
      group("$cuisine", avg("averageScore", "$grades.score"), sum("inspections", 1)),
      filter(gte("inspections", minInspections)),
      sort(if (cleanestFirst) ascending("averageScore") else descending("averageScore")),
      limit(top)
    ))(d => ScoreRow(text(d, "_id"), number(d, "averageScore"), number(d, "inspections").toLong))

  /** 4. Restaurants with the lowest average score, optionally inside one borough. */
  def topRatedRestaurants(borough: Option[String], minInspections: Int, top: Int): Report[RatedRestaurant] = {
    val boroughStage = borough.map(b => filter(equal("borough", b))).toList // no stage when None
    run(boroughStage ++ Seq(
      project(fields(
        include("restaurant_id", "name", "cuisine", "borough"),
        computed("averageScore", Document("$avg" -> "$grades.score")),
        // $ifNull treats a missing grades field as an empty list
        computed("inspections", Document("$size" -> Document("$ifNull" -> new BsonArray(java.util.List.of(new BsonString("$grades"), new BsonArray()))))),
        excludeId()
      )),
      filter(and(gte("inspections", minInspections), gte("averageScore", 0), notEqual("name", ""))),
      sort(orderBy(ascending("averageScore"), descending("inspections"))),
      limit(top)
    ))(d => RatedRestaurant(text(d, "restaurant_id"), text(d, "name"), text(d, "cuisine"), text(d, "borough"),
        number(d, "averageScore"), number(d, "inspections").toLong))
  }

  /** 5. How often each grade (A, B, C, ...) was given across all inspections. */
  def gradeDistribution(): Report[CountRow] =
    run(Seq(
      unwind("$grades"),
      group("$grades.grade", sum("count", 1)),
      sort(descending("count"))
    ))(d => CountRow(text(d, "_id"), number(d, "count").toLong))

  /** 6. The most common cuisine in every borough (two $group stages in a row). */
  def favouriteCuisinePerBorough(): Report[BoroughFavourite] =
    run(Seq(
      filter(notEqual("borough", "Missing")),
      group(Document("borough" -> "$borough", "cuisine" -> "$cuisine"), sum("count", 1)),
      sort(descending("count")),
      group("$_id.borough", first("cuisine", "$_id.cuisine"), first("count", "$count"), sum("boroughTotal", "$count")),
      sort(descending("boroughTotal"))
    ))(d => BoroughFavourite(text(d, "_id"), text(d, "cuisine"), number(d, "count").toLong, number(d, "boroughTotal").toLong))
}
