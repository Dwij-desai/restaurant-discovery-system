import scala.util.{Failure, Success, Try}
import com.mongodb.{MongoSecurityException, MongoTimeoutException}

/** Entry point: connects to MongoDB, builds the parts of the app and opens the main menu. */
object RestaurantApp {

  def main(args: Array[String]): Unit = {
    Ui.clear()
    Ui.banner()
    println()
    Config.mongoUri match {
      case None =>
        Ui.error("No MongoDB connection string found.")
        Ui.note("  Copy .env.example to .env and paste your Atlas connection string after MONGODB_URI=")
      case Some(uri) =>
        connect(uri).foreach(start)
    }
  }

  /** Opens the connection and pings the server. Common problems are explained in plain words. */
  private def connect(uri: String): Option[Database] = {
    Ui.info("Connecting to MongoDB …")
    Try(Database(uri)) match {
      case Failure(e) =>
        Ui.error(s"The connection string is not valid: ${e.getMessage}")
        None
      case Success(db) =>
        Try(Ui.timed(db.ping())) match {
          case Success((_, millis)) =>
            Ui.success(s"Connected to ${Ui.bold(db.host)}  ${Views.timing(millis)}")
            Some(db)
          case Failure(e) =>
            e match {
              case _: MongoSecurityException =>
                Ui.error("Login failed. Check the username and password in MONGODB_URI.")
              case _: MongoTimeoutException =>
                Ui.error("Could not reach the cluster within 10 seconds.")
                Ui.note("  In Atlas › Network Access, allow your current IP address, then try again.")
              case other =>
                Ui.error(s"Could not connect: ${other.getMessage}")
            }
            db.close()
            None
        }
    }
  }

  private def start(db: Database): Unit = {
    val repo = RestaurantRepository(db.restaurants)
    try {
      val total = repo.count()
      if (total == 0) {
        Ui.warn(s"${Config.DatabaseName}.${Config.CollectionName} is empty.")
        Ui.note("  In Atlas, use \"Load Sample Dataset\" on your cluster, then run the app again.")
      } else {
        Ui.success(s"${Config.DatabaseName}.${Config.CollectionName} has ${Ui.bold(Ui.number(total))} restaurants")
        val menu = MainMenu(db, repo, CrudActions(repo), AnalyticsService(db.restaurants), IndexManager(db.restaurants))
        Ui.pause()
        menu.run()
        Ui.clear()
        Ui.banner()
        println()
        Ui.success("Connection closed. Goodbye!")
        println()
      }
    } catch {
      case _: InputClosedException => println()
    } finally db.close()
  }
}
