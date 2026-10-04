import org.mongodb.scala.*
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters.*
import org.mongodb.scala.model.Sorts.*
import org.mongodb.scala.model.{PushOptions, Updates}

/** Generic CRUD operations. Any stored type T with an id of type ID can implement this. */
trait Repository[T, ID] {
  def insert(item: T): Unit
  def findById(id: ID): Option[T]
  def update(id: ID, change: Bson): Boolean
  def delete(id: ID): Boolean
  def count(): Long
}

/** Search results: the first page of matches plus how many matched in total. */
case class SearchResult(restaurants: Seq[Restaurant], totalMatches: Long, millis: Long)

/** All reads and writes for the restaurants collection go through this class. */
class RestaurantRepository(collection: MongoCollection[Document]) extends Repository[Restaurant, String] {
  import Sync.*

  private def byId(id: String): Bson = equal("restaurant_id", id)

  // ---------- Create ----------

  def insert(restaurant: Restaurant): Unit =
    collection.insertOne(restaurant.toDocument).results()

  /** New ids continue after the highest restaurant_id already in the collection. */
  def nextRestaurantId(): String = {
    val highest = collection.find().sort(descending("restaurant_id")).limit(1).firstResult()
      .map(Restaurant.fromDocument)
      .flatMap(_.restaurantId.toLongOption)
    highest match {
      case Some(n) => (n + 1).toString
      case None    => "90000001"
    }
  }

  // ---------- Read ----------

  def findById(id: String): Option[Restaurant] =
    collection.find(byId(id)).first().firstResult().map(Restaurant.fromDocument)

  def count(): Long = collection.countDocuments().results().head

  def search(search: SearchFilter, limit: Int): SearchResult = {
    val filter = SearchFilter.toFilter(search)
    val (restaurants, millis) = Ui.timed {
      val query = collection.find(filter).limit(limit)
      // Name searches come back in name order straight from the name_1 index
      val sorted = search match {
        case _: ByName => query.sort(ascending("name"))
        case _         => query
      }
      sorted.results().map(Restaurant.fromDocument)
    }
    val total = collection.countDocuments(filter).results().head
    SearchResult(restaurants, total, millis)
  }

  /** All distinct cuisine names, used to match what the user types. */
  lazy val cuisines: Seq[String] =
    collection.distinct[String]("cuisine").results().filter(_.nonEmpty).sorted

  // ---------- Update ----------

  def update(id: String, change: Bson): Boolean =
    collection.updateOne(byId(id), change).results().head.getModifiedCount == 1

  def rename(id: String, newName: String): Boolean        = update(id, Updates.set("name", newName))
  def changeCuisine(id: String, cuisine: String): Boolean = update(id, Updates.set("cuisine", cuisine))
  def changeBorough(id: String, borough: String): Boolean = update(id, Updates.set("borough", borough))

  def changeAddress(id: String, building: String, street: String, zipcode: String): Boolean =
    update(id, Updates.combine(
      Updates.set("address.building", building),
      Updates.set("address.street", street),
      Updates.set("address.zipcode", zipcode)
    ))

  /** Adds a new inspection to the front of the grades array (newest first, like the dataset). */
  def addInspection(id: String, grade: Grade): Boolean =
    update(id, Updates.pushEach("grades", PushOptions().position(0), Restaurant.gradeToBson(grade)))

  // ---------- Delete ----------

  def delete(id: String): Boolean =
    collection.deleteOne(byId(id)).results().head.getDeletedCount == 1
}
