import scala.annotation.tailrec

/** The Add, View, Update and Delete screens (the CRUD part of the app). */
class CrudActions(repo: RestaurantRepository) {

  // ---------- Create ----------

  def addRestaurant(): Unit = {
    Ui.header("Add Restaurant")
    Ui.note("Fill in the details. The restaurant ID is generated for you.")
    println()

    val name = Ui.askNonEmpty("Restaurant name:")
    val cuisine = chooseCuisine()
    val borough = Views.askBorough()
    val building = Ui.askNonEmpty("Building number:")
    val street = Ui.askNonEmpty("Street:")
    val zipcode = Views.askZip("ZIP code (5 digits):")
    val firstInspection = if (Ui.confirm("Record a first inspection now?")) Some(Views.askInspection()) else None

    val restaurant = Restaurant(
      restaurantId = repo.nextRestaurantId(),
      name = name,
      cuisine = cuisine,
      borough = borough,
      address = Address(building, street, zipcode, coord = Nil),
      grades = firstInspection.toList
    )

    println()
    Views.restaurantCard(restaurant)
    println()
    if (Ui.confirm("Save this restaurant to MongoDB?")) {
      val before = repo.count()
      repo.insert(restaurant)
      Ui.success(s"Saved with restaurant ID ${Ui.bold(restaurant.restaurantId)}  (insertOne)")
      Ui.info(s"Restaurants in the collection: ${Ui.number(before)} → ${Ui.bold(Ui.number(repo.count()))}")
    } else Ui.warn("Not saved.")
  }

  // ---------- Read (one restaurant) ----------

  def viewRestaurant(): Unit = {
    Ui.header("View Restaurant")
    pickRestaurant().foreach { r =>
      println()
      Views.restaurantCard(r)
    }
  }

  // ---------- Update ----------

  def updateRestaurant(): Unit = {
    Ui.header("Update Restaurant")
    pickRestaurant().foreach { r =>
      println()
      Ui.note("Before:")
      Views.restaurantCard(r)
      println()
      val options = Seq("Name", "Cuisine", "Borough", "Address (building, street, ZIP)", "Add a new inspection", "Cancel")
      val choice = Ui.choose("What do you want to change?", options)(identity)
      val id = r.restaurantId

      val changed: Option[Boolean] = choice match {
        case "Name" => Some(repo.rename(id, Ui.askNonEmpty("New name:")))
        case "Cuisine" => Views.askCuisine(repo.cuisines, allowNew = true).map(c => repo.changeCuisine(id, c))
        case "Borough" => Some(repo.changeBorough(id, Views.askBorough()))
        case "Add a new inspection" => Some(repo.addInspection(id, Views.askInspection()))
        case "Cancel" => None
        case _ =>
          val building = Ui.askNonEmpty("Building number:")
          val street = Ui.askNonEmpty("Street:")
          Some(repo.changeAddress(id, building, street, Views.askZip("ZIP code (5 digits):")))
      }

      changed match {
        case Some(true) =>
          Ui.success("Updated: 1 document modified  (updateOne)")
          println()
          Ui.note("After:")
          repo.findById(id).foreach(Views.restaurantCard)
        case Some(false) => Ui.warn("Nothing changed (the new value was the same as the old one).")
        case None        => Ui.warn("Update cancelled.")
      }
    }
  }

  // ---------- Delete ----------

  def deleteRestaurant(): Unit = {
    Ui.header("Delete Restaurant")
    pickRestaurant().foreach { r =>
      println()
      Views.restaurantCard(r)
      println()
      if (Ui.confirm(Ui.red("Delete this restaurant permanently?"))) {
        val before = repo.count()
        if (repo.delete(r.restaurantId)) {
          Ui.success(s"Deleted restaurant ${Ui.bold(r.restaurantId)}  (deleteOne)")
          Ui.info(s"Restaurants in the collection: ${Ui.number(before)} → ${Ui.bold(Ui.number(repo.count()))}")
          val stillThere = repo.findById(r.restaurantId).isDefined
          Ui.info(s"Looking up ID ${r.restaurantId} again: ${if (stillThere) Ui.red("still found") else Ui.green("not found ✔")}")
        } else Ui.error("Nothing was deleted.")
      } else Ui.warn("Delete cancelled.")
    }
  }

  // ---------- helpers ----------

  /** Keeps asking until the user settles on a cuisine. */
  @tailrec
  private def chooseCuisine(): String =
    Views.askCuisine(repo.cuisines, allowNew = true) match {
      case Some(cuisine) => cuisine
      case None          => chooseCuisine()
    }

  /** The user can type a restaurant ID, or part of a name and then pick from the matches. */
  def pickRestaurant(): Option[Restaurant] = {
    val input = Ui.askNonEmpty("Restaurant ID, or part of its name:")
    if (input.forall(_.isDigit)) {
      val found = repo.findById(input)
      if (found.isEmpty) Ui.error(s"No restaurant has ID $input.")
      found
    } else {
      repo.search(ByName(input), limit = 12).restaurants match {
        case Seq() =>
          Ui.error(s"No restaurant name contains \"$input\".")
          None
        case Seq(only) => Some(only)
        case several =>
          Some(Ui.choose("Which one?", several)(r =>
            s"${Views.displayName(r.name)}  ${Ui.dim(s"${r.cuisine} · ${r.borough} · ID ${r.restaurantId}")}"))
      }
    }
  }
}
