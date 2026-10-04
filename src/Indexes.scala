import scala.jdk.CollectionConverters.*
import scala.util.Try
import com.mongodb.ExplainVerbosity
import org.bson.{BsonArray, BsonDocument, BsonString, BsonValue}
import org.mongodb.scala.*
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.{IndexOptions, Indexes}

/** An index this app creates, and the feature that relies on it. */
case class IndexPlan(name: String, keys: Bson, keysText: String, kind: String, usedBy: String)

/** An index that currently exists on the collection. */
case class IndexInfo(name: String, keysText: String, fieldCount: Int)

/** The important numbers from MongoDB's explain() for one query. */
case class QueryPlan(stages: List[String], indexName: Option[String], returned: Long, keysExamined: Long, docsExamined: Long, millis: Long) {
  def usedIndex: Boolean = indexName.isDefined
}

/** Creates, lists and explains the indexes on the restaurants collection. */
class IndexManager(collection: MongoCollection[Document]) {
  import Sync.*

  /** Every index here backs at least one query in the app. Two of them are compound. */
  val plans: List[IndexPlan] = List(
    IndexPlan("restaurant_id_1", Indexes.ascending("restaurant_id"), "{ restaurant_id: 1 }", "single", "View / update / delete by ID"),
    IndexPlan("name_1", Indexes.ascending("name"), "{ name: 1 }", "single", "Search by name (A–Z order)"),
    IndexPlan("cuisine_1_borough_1", Indexes.ascending("cuisine", "borough"), "{ cuisine: 1, borough: 1 }", "compound", "Search by cuisine, cuisine + borough"),
    IndexPlan("borough_1_name_1", Indexes.ascending("borough", "name"), "{ borough: 1, name: 1 }", "compound", "Search by borough, top-rated by borough"),
    IndexPlan("address.zipcode_1", Indexes.ascending("address.zipcode"), "{ address.zipcode: 1 }", "single", "Search by ZIP code"),
    IndexPlan("grades.score_1", Indexes.ascending("grades.score"), "{ grades.score: 1 }", "multikey", "Search by inspection score range")
  )

  /** Creates any planned index that is missing. Returns each plan with true if it was newly created. */
  def createMissing(): List[(IndexPlan, Boolean)] = {
    val existingNames = list().map(_.name).toSet
    plans.map { plan =>
      if (existingNames.contains(plan.name)) (plan, false)
      else {
        collection.createIndex(plan.keys, IndexOptions().name(plan.name)).results()
        (plan, true)
      }
    }
  }

  /** Drops this app's indexes (never the built-in _id index). */
  def dropAppIndexes(): Int = {
    val existingNames = list().map(_.name).toSet
    val toDrop = plans.map(_.name).filter(existingNames.contains)
    toDrop.foreach(name => collection.dropIndex(name).results())
    toDrop.size
  }

  def list(): Seq[IndexInfo] =
    collection.listIndexes().results().map { doc =>
      val bson = doc.toBsonDocument
      val keys = bson.getDocument("key")
      IndexInfo(BsonFields.text(bson, "name"), keysToText(keys), keys.size)
    }

  /** How many times each index has been used since the server started ($indexStats). */
  def usageCounts(): Map[String, Long] =
    Try {
      collection.aggregate(Seq(Document("$indexStats" -> Document()))).results().map { doc =>
        val bson = doc.toBsonDocument
        BsonFields.text(bson, "name") -> BsonFields.number(BsonFields.subDocument(bson, "accesses"), "ops").toLong
      }.toMap
    }.getOrElse(Map.empty) // some cluster tiers do not allow $indexStats

  /** Runs explain() on a search. With forceCollectionScan the index is ignored on purpose. */
  def explain(search: SearchFilter, forceCollectionScan: Boolean): QueryPlan = {
    val query = collection.find(SearchFilter.toFilter(search))
    val hinted = if (forceCollectionScan) query.hint(Document("$natural" -> 1)) else query
    val result = hinted.explain[Document](ExplainVerbosity.EXECUTION_STATS).results().head.toBsonDocument

    val winningPlan = BsonFields.subDocument(BsonFields.subDocument(result, "queryPlanner"), "winningPlan")
    val steps = planSteps(winningPlan)
    val stats = BsonFields.subDocument(result, "executionStats")
    QueryPlan(
      stages = steps.map(_._1).reverse, // innermost stage first, the order data flows
      indexName = steps.flatMap(_._2).headOption,
      returned = BsonFields.number(stats, "nReturned").toLong,
      keysExamined = BsonFields.number(stats, "totalKeysExamined").toLong,
      docsExamined = BsonFields.number(stats, "totalDocsExamined").toLong,
      millis = BsonFields.number(stats, "executionTimeMillis").toLong
    )
  }

  /** Walks the plan tree (it can be nested many levels) and collects (stage, index name) pairs. */
  private def planSteps(value: BsonValue): List[(String, Option[String])] = value match {
    case doc: BsonDocument =>
      val here = doc.get("stage") match {
        case s: BsonString => List((s.getValue, Option(doc.get("indexName")).collect { case n: BsonString => n.getValue }))
        case _             => Nil
      }
      here ++ doc.values.asScala.toList.flatMap(planSteps)
    case array: BsonArray => array.getValues.asScala.toList.flatMap(planSteps)
    case _                => Nil
  }

  private def keysToText(keys: BsonDocument): String =
    keys.entrySet.asScala.toList.map { entry => // toList keeps the key order
      val direction = BsonFields.toInt(entry.getValue).map(_.toString).getOrElse(entry.getValue.toString)
      s"${entry.getKey}: $direction"
    }.mkString("{ ", ", ", " }")
}
