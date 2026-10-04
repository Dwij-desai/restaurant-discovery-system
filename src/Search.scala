import java.util.regex.Pattern
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters.*

/**
 * Every kind of search the app supports. A sealed trait means the compiler
 * knows all the cases, so the pattern match in toFilter must handle each one.
 */
sealed trait SearchFilter {
  def description: String
}

case class ByName(text: String)                             extends SearchFilter { def description = s"""name contains "$text"""" }
case class ByCuisine(cuisine: String)                       extends SearchFilter { def description = s"cuisine = $cuisine" }
case class ByBorough(borough: String)                       extends SearchFilter { def description = s"borough = $borough" }
case class ByZipCode(zipcode: String)                       extends SearchFilter { def description = s"ZIP code = $zipcode" }
case class ByScoreRange(min: Int, max: Int)                 extends SearchFilter { def description = s"an inspection score between $min and $max" }
case class ByCuisineAndBorough(cuisine: String, borough: String) extends SearchFilter { def description = s"cuisine = $cuisine and borough = $borough" }
case object AddedByThisApp                                  extends SearchFilter { def description = "added through this app" }

object SearchFilter {

  /** Turns a search into a MongoDB query filter. */
  def toFilter(search: SearchFilter): Bson = search match {
    case ByName(text)          => regex("name", Pattern.quote(text), "i") // case-insensitive "contains"
    case ByCuisine(cuisine)    => equal("cuisine", cuisine)
    case ByBorough(borough)    => equal("borough", borough)
    case ByZipCode(zipcode)    => equal("address.zipcode", zipcode)
    case ByScoreRange(lo, hi)  => elemMatch("grades", and(gte("score", lo), lte("score", hi)))
    case ByCuisineAndBorough(cuisine, borough) => and(equal("cuisine", cuisine), equal("borough", borough))
    case AddedByThisApp        => equal("added_via", Restaurant.AddedViaTag)
  }

  /** The index each search is designed to use (shown in the results footer). */
  def expectedIndex(search: SearchFilter): String = search match {
    case _: ByName              => "name_1"
    case _: ByCuisine           => "cuisine_1_borough_1"
    case _: ByBorough           => "borough_1_name_1"
    case _: ByZipCode           => "address.zipcode_1"
    case _: ByScoreRange        => "grades.score_1"
    case _: ByCuisineAndBorough => "cuisine_1_borough_1"
    case AddedByThisApp         => "none (small result)"
  }
}
