name := "AR"
version := "0.1"
scalaVersion := "2.10.4"
// scalaVersion := "2.11.8"
libraryDependencies ++= Seq(
  "org.apache.spark" % "spark-core_2.10" % "2.2.0" % "provided",
)
// https://mvnrepository.com/artifact/org.apache.spark/spark-mllib
libraryDependencies += "org.apache.spark" %% "spark-mllib" % "2.2.1"
