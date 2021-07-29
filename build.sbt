name := "SparkOrderConsumer"

version := "0.1"

scalaVersion := "2.12.10"

val sparkVersion = "3.0.0"

val sparkDependencies = Seq(
    "org.apache.spark" %% "spark-core" % sparkVersion,
    "org.apache.spark" %% "spark-sql" % sparkVersion,
    "org.apache.spark" %% "spark-sql-kafka-0-10" % sparkVersion,
    "org.apache.spark" %% "spark-streaming" % sparkVersion,
    "org.apache.spark" %% "spark-avro" % sparkVersion,
)

libraryDependencies ++= sparkDependencies

mainClass in assembly := some("com.myntra.orders.purchaseSpark")
assemblyJarName := "desired_jar_name_after_assembly.jar"

assemblyMergeStrategy in assembly := {
    case PathList("META-INF", xs @ _*) => MergeStrategy.discard
    case x => MergeStrategy.first
}

