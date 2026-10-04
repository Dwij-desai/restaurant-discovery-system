import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using
import org.mongodb.scala.*

/** Settings for the app. The connection string is never written in the code. */
object Config {
  val DatabaseName   = "sample_restaurants"
  val CollectionName = "restaurants"

  /** MONGODB_URI from the environment, or else from a .env file in the current folder. */
  def mongoUri: Option[String] =
    sys.env.get("MONGODB_URI").filter(_.nonEmpty)
      .orElse(readEnvFile(".env").get("MONGODB_URI"))
      .filter(_.startsWith("mongodb"))

  /** Reads KEY=value lines. A missing file simply gives an empty map. */
  private def readEnvFile(path: String): Map[String, String] =
    Using(Source.fromFile(path)) { source =>
      source.getLines()
        .map(_.trim)
        .filter(line => line.nonEmpty && !line.startsWith("#") && line.contains("="))
        .map { line =>
          val (key, value) = line.splitAt(line.indexOf('='))
          key.trim -> value.drop(1).trim.stripPrefix("\"").stripSuffix("\"")
        }
        .toMap
    }.getOrElse(Map.empty)
}

/**
 * The official MongoDB Scala driver is asynchronous (it returns Observables).
 * A CLI runs one step at a time, so these helpers simply wait for the result.
 */
object Sync {
  private val timeout = 60.seconds

  extension [T](observable: Observable[T]) {
    def results(): Seq[T]        = Await.result(observable.toFuture(), timeout)
    def firstResult(): Option[T] = Await.result(observable.headOption(), timeout)
  }
}

/** Owns the connection to MongoDB. The client itself stays private (encapsulation). */
class Database(uri: String) {
  import Sync.*

  private val client: MongoClient = MongoClient(
    MongoClientSettings.builder()
      .applyConnectionString(ConnectionString(uri))
      .applyToClusterSettings(b => b.serverSelectionTimeout(10, TimeUnit.SECONDS))
      .applicationName("restaurant-discovery-cli")
      .build()
  )

  private val database: MongoDatabase = client.getDatabase(Config.DatabaseName)

  val restaurants: MongoCollection[Document] = database.getCollection(Config.CollectionName)

  /** Fails with an exception if the server cannot be reached. */
  def ping(): Unit = database.runCommand(Document("ping" -> 1)).firstResult()

  /** Host name of the cluster we are connected to, for the status line. */
  def host: String = ConnectionString(uri).getHosts.asScala.headOption.getOrElse("?")

  def close(): Unit = client.close()
}
