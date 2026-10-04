import scala.annotation.tailrec
import scala.io.StdIn

/** Thrown when the input stream is closed (Ctrl+D), so the app can exit cleanly. */
class InputClosedException extends RuntimeException("Input was closed")

/** Everything that draws on the terminal: colours, boxes, tables, bar charts and prompts. */
object Ui {

  // ---------- colours (256-colour ANSI codes; set NO_COLOR=1 to turn them off) ----------

  private val colorOn: Boolean = !sys.env.contains("NO_COLOR")

  private def paint(code: String, text: String): String =
    if (colorOn) s"\u001b[${code}m$text\u001b[0m" else text

  def bold(t: String): String   = paint("1", t)
  def dim(t: String): String    = paint("2", t)
  def accent(t: String): String = paint("38;5;209", t) // coral, the app's brand colour
  def teal(t: String): String   = paint("38;5;44", t)
  def violet(t: String): String = paint("38;5;141", t)
  def green(t: String): String  = paint("38;5;78", t)
  def yellow(t: String): String = paint("38;5;221", t)
  def red(t: String): String    = paint("38;5;203", t)

  // ---------- text helpers ----------

  private val ansiCodes = "\u001b\\[[0-9;]*m".r

  /** Length of the text as it appears on screen (colour codes take no space). */
  def visibleLength(t: String): Int = ansiCodes.replaceAllIn(t, "").length

  def padRight(t: String, width: Int): String = t + " " * (width - visibleLength(t)).max(0)
  def padLeft(t: String, width: Int): String  = " " * (width - visibleLength(t)).max(0) + t

  def truncate(t: String, max: Int): String = if (t.length <= max) t else t.take(max - 1) + "…"

  def number(n: Long): String = f"$n%,d"

  // ---------- screen layout ----------

  def clear(): Unit = print("\u001b[2J\u001b[H")

  def banner(): Unit = {
    val width = 64
    val lines = Seq(
      "",
      bold(accent("R E S T A U R A N T   D I S C O V E R Y")),
      "& Analytics System    " + dim("Scala 3  ·  MongoDB Atlas"),
      ""
    )
    println()
    println(accent("  ╭" + "─" * width + "╮"))
    lines.foreach(l => println(accent("  │") + "   " + padRight(l, width - 3) + accent("│")))
    println(accent("  ╰" + "─" * width + "╯"))
  }

  /** Small line at the top of each screen showing where you are. */
  def breadcrumb(section: String): Unit = {
    println()
    println("  " + bold(accent("RESTAURANT DISCOVERY")) + dim(s"  ›  $section"))
  }

  def header(title: String): Unit = {
    println()
    println("  " + bold(violet(title)))
    println("  " + violet("─" * visibleLength(title).max(40)))
  }

  def success(msg: String): Unit = println("  " + green("✔ ") + msg)
  def error(msg: String): Unit   = println("  " + red("✘ ") + msg)
  def warn(msg: String): Unit    = println("  " + yellow("! ") + msg)
  def info(msg: String): Unit    = println("  " + teal("› ") + msg)
  def note(msg: String): Unit    = println("  " + dim(msg))

  /** Prints a numbered menu. Each item is (key the user types, label). */
  def menu(items: Seq[(String, String)]): Unit =
    items.foreach { case (key, label) =>
      val styledKey = if (key == "0") dim(" 0") else accent(bold(padLeft(key, 2)))
      val styledLabel = if (key == "0") dim(label) else label
      println(s"   $styledKey  $styledLabel")
    }

  /** Prints rows as a boxed table. Each column is as wide as its longest cell. */
  def table(headers: Seq[String], rows: Seq[Seq[String]], rightAligned: Set[Int] = Set.empty): Unit = {
    val widths = headers.indices.map(i => (headers(i) +: rows.map(_(i))).map(visibleLength).max)

    def border(left: String, middle: String, right: String): String =
      dim("  " + left + widths.map(w => "─" * (w + 2)).mkString(middle) + right)

    def row(values: Seq[String], style: String => String): String = {
      val cells = values.zipWithIndex.map { case (value, i) =>
        val padded = if (rightAligned.contains(i)) padLeft(value, widths(i)) else padRight(value, widths(i))
        " " + style(padded) + " "
      }
      "  " + dim("│") + cells.mkString(dim("│")) + dim("│")
    }

    println(border("╭", "┬", "╮"))
    println(row(headers, h => bold(teal(h))))
    println(border("├", "┼", "┤"))
    rows.foreach(r => println(row(r, identity)))
    println(border("╰", "┴", "╯"))
  }

  /** A box with a title and "label  value" lines, used to show one restaurant. */
  def card(title: String, fields: Seq[(String, String)]): Unit = {
    val labelWidth = fields.map(_._1.length).maxOption.getOrElse(0)
    val lines = fields.map { case (label, value) => dim(padRight(label, labelWidth)) + "   " + value }
    val inner = ((visibleLength(title) + 4) +: lines.map(l => visibleLength(l) + 2)).max
    println("  " + accent("╭─ ") + bold(title) + " " + accent("─" * (inner - visibleLength(title) - 3) + "╮"))
    lines.foreach(l => println("  " + accent("│") + " " + padRight(l, inner - 1) + accent("│")))
    println("  " + accent("╰" + "─" * inner + "╯"))
  }

  private val barPieces = Vector("", "▏", "▎", "▍", "▌", "▋", "▊", "▉")

  /** A bar `units` characters long. Fractions use thinner blocks so bars look smooth. */
  def bar(units: Double): String = "█" * units.toInt + barPieces(((units - units.toInt) * 8).toInt)

  /** Horizontal bar chart. Bars are scaled so the biggest value fills barWidth characters. */
  def barChart(data: Seq[(String, Double)], valueText: Double => String, barWidth: Int = 34): Unit = {
    val maxValue = data.map(_._2).maxOption.getOrElse(1.0).max(0.0001)
    val labelWidth = data.map(_._1.length).maxOption.getOrElse(0)
    data.zipWithIndex.foreach { case ((label, value), i) =>
      val color: String => String = if (i == 0) accent else teal
      val line = color(bar(value / maxValue * barWidth))
      println("  " + padRight(label, labelWidth) + "  " + padRight(line, barWidth + 1) + " " + bold(valueText(value)))
    }
  }

  /** Runs a piece of work and also returns how many milliseconds it took. */
  def timed[T](work: => T): (T, Long) = {
    val start = System.nanoTime()
    val result = work
    (result, (System.nanoTime() - start) / 1_000_000)
  }

  // ---------- input ----------

  /** Reads one line. Returns it trimmed, or stops the app if input was closed. */
  def ask(prompt: String): String = {
    print("  " + teal("? ") + prompt + " ")
    Option(StdIn.readLine()) match {
      case Some(line) => line.trim
      case None       => throw new InputClosedException
    }
  }

  @tailrec
  def askNonEmpty(prompt: String): String = {
    val answer = ask(prompt)
    if (answer.nonEmpty) answer
    else {
      error("This field cannot be empty.")
      askNonEmpty(prompt)
    }
  }

  /** Empty answer means "skip" and gives None. */
  def askOptional(prompt: String): Option[String] = Some(ask(prompt)).filter(_.nonEmpty)

  @tailrec
  def askInt(prompt: String, min: Int, max: Int): Int =
    ask(prompt).toIntOption match {
      case Some(n) if n >= min && n <= max => n
      case Some(_) =>
        error(s"Please enter a number from $min to $max.")
        askInt(prompt, min, max)
      case None =>
        error("That is not a number.")
        askInt(prompt, min, max)
    }

  /** Shows a numbered list and returns the item the user picks. */
  def choose[T](prompt: String, options: Seq[T])(label: T => String): T = {
    options.zipWithIndex.foreach { case (option, i) => println(s"   ${accent(padLeft((i + 1).toString, 2))}  ${label(option)}") }
    options(askInt(prompt, 1, options.size) - 1)
  }

  def confirm(prompt: String): Boolean =
    ask(prompt + dim(" (y/n)")).toLowerCase match {
      case "y" | "yes" => true
      case _           => false
    }

  def pause(): Unit = {
    println()
    ask(dim("Press Enter to continue"))
  }
}
