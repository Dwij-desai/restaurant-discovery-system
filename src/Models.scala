import java.time.{Instant, LocalDate, ZoneOffset}
import scala.jdk.CollectionConverters.*
import org.bson.{BsonArray, BsonDateTime, BsonDocument, BsonDouble, BsonInt32, BsonInt64, BsonNull, BsonNumber, BsonString, BsonValue}
import org.mongodb.scala.Document

/** One health inspection. In New York a LOWER score is better (fewer violations). */
case class Grade(date: Option[LocalDate], grade: String, score: Option[Int])

case class Address(building: String, street: String, zipcode: String, coord: List[Double]) {
  def oneLine: String = {
    val streetPart = Seq(building, street).filter(_.nonEmpty).mkString(" ")
    if (zipcode.isEmpty) streetPart else s"$streetPart, NY $zipcode"
  }
}

/** A restaurant as stored in sample_restaurants.restaurants. */
case class Restaurant(
    restaurantId: String,
    name: String,
    cuisine: String,
    borough: String,
    address: Address,
    grades: List[Grade]
) {

  /** The most recent inspection, if there is one. */
  def latestGrade: Option[Grade] = grades.maxByOption(_.date.map(_.toEpochDay))

  /** Average of all inspection scores, or None when no scores exist. */
  def averageScore: Option[Double] = {
    val scores = grades.flatMap(_.score)
    if (scores.isEmpty) None else Some(scores.sum.toDouble / scores.size)
  }

  /** Converts this restaurant into a MongoDB document (same shape as the sample dataset). */
  def toDocument: Document = Document(
    "address" -> Document(
      "building" -> address.building,
      "coord"    -> new BsonArray(address.coord.map(c => new BsonDouble(c)).asJava),
      "street"   -> address.street,
      "zipcode"  -> address.zipcode
    ),
    "borough"       -> borough,
    "cuisine"       -> cuisine,
    "grades"        -> new BsonArray(grades.map(Restaurant.gradeToBson).asJava),
    "name"          -> name,
    "restaurant_id" -> restaurantId,
    "added_via"     -> Restaurant.AddedViaTag // marks records created by this app
  )
}

object Restaurant {

  val Boroughs: List[String] = List("Manhattan", "Brooklyn", "Queens", "Bronx", "Staten Island")
  val Grades: List[String]   = List("A", "B", "C")
  val AddedViaTag: String    = "restaurant-cli"

  def isValidZip(zip: String): Boolean = zip.length == 5 && zip.forall(_.isDigit)

  /** Reads a document from the collection. Missing or odd fields fall back to safe defaults. */
  def fromDocument(doc: Document): Restaurant = {
    import BsonFields.*
    val bson = doc.toBsonDocument
    val address = subDocument(bson, "address")
    Restaurant(
      restaurantId = text(bson, "restaurant_id"),
      name = text(bson, "name"),
      cuisine = text(bson, "cuisine"),
      borough = text(bson, "borough"),
      address = Address(
        building = text(address, "building"),
        street = text(address, "street"),
        zipcode = text(address, "zipcode"),
        coord = array(address, "coord").flatMap(toDouble)
      ),
      grades = array(bson, "grades").collect { case g: BsonDocument =>
        Grade(
          date = Option(g.get("date")).flatMap(toDate),
          grade = text(g, "grade"),
          score = Option(g.get("score")).flatMap(toInt)
        )
      }
    )
  }

  def gradeToBson(g: Grade): BsonDocument = {
    val date: BsonValue = g.date match {
      case Some(d) => new BsonDateTime(d.atStartOfDay(ZoneOffset.UTC).toInstant.toEpochMilli)
      case None    => new BsonNull()
    }
    val score: BsonValue = g.score match {
      case Some(s) => new BsonInt32(s)
      case None    => new BsonNull()
    }
    new BsonDocument().append("date", date).append("grade", new BsonString(g.grade)).append("score", score)
  }

  /** NYC rule: a score of 0-13 earns an A, 14-27 a B, and 28 or more a C. */
  def gradeFor(score: Int): String =
    if (score <= 13) "A"
    else if (score <= 27) "B"
    else "C"
}

/** Small helpers that read BSON values safely. Pattern matching checks each value's type. */
object BsonFields {

  def text(doc: BsonDocument, key: String): String =
    Option(doc.get(key)) match {
      case Some(s: BsonString) => s.getValue
      case _                   => ""
    }

  def subDocument(doc: BsonDocument, key: String): BsonDocument =
    Option(doc.get(key)) match {
      case Some(d: BsonDocument) => d
      case _                     => new BsonDocument()
    }

  def array(doc: BsonDocument, key: String): List[BsonValue] =
    Option(doc.get(key)) match {
      case Some(a: BsonArray) => a.getValues.asScala.toList
      case _                  => Nil
    }

  def toInt(value: BsonValue): Option[Int] = value match {
    case n: BsonInt32  => Some(n.getValue)
    case n: BsonInt64  => Some(n.getValue.toInt)
    case n: BsonDouble => Some(n.getValue.round.toInt)
    case _             => None // null or missing score
  }

  def toDouble(value: BsonValue): Option[Double] = value match {
    case n: BsonDouble => Some(n.getValue)
    case n: BsonInt32  => Some(n.getValue.toDouble)
    case _             => None
  }

  def number(doc: BsonDocument, key: String): Double =
    Option(doc.get(key)) match {
      case Some(n: BsonNumber) => n.doubleValue
      case _                   => 0.0
    }

  def toDate(value: BsonValue): Option[LocalDate] = value match {
    case d: BsonDateTime => Some(Instant.ofEpochMilli(d.getValue).atZone(ZoneOffset.UTC).toLocalDate)
    case _               => None
  }
}
