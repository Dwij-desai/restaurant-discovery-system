// Build settings for Scala CLI (no sbt needed).
// Run the app from this folder with:   scala-cli run .

//> using scala 3.8.4
//> using dep org.mongodb.scala::mongo-scala-driver:5.13.0
// Turns off the driver's internal log lines so the CLI output stays clean
//> using dep org.slf4j:slf4j-nop:2.0.20
//> using mainClass RestaurantApp
// Java 24+ prints "sun.misc.Unsafe" warnings for code inside the Scala driver; this hides them.
// The first flag makes older Java versions (17, 21) simply ignore the second one.
//> using javaOpt -XX:+IgnoreUnrecognizedVMOptions
//> using javaOpt --sun-misc-unsafe-memory-access=allow
