package com.myntra.orders

import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.types._

object purchaseSpark {

    def main(args: Array[String]): Unit = {

        Logger.getLogger("org").setLevel(Level.ERROR)

        val topicName = "dbserver1.inventory.purchase_detail"
        val bootstrapServer = "localhost:9092"

        val spark = SparkSession.builder()
            .appName("SparkDebeziumPurchaseDetailConsumer")
            .master("local[*]")
            .config("spark.streaming.stopGracefullyOnShutdown", "true")
            .config("spark.sql.shuffle.partitions", 1)
            .getOrCreate()

        val kafkaDF = spark.readStream
            .format("kafka")
            .option("kafka.bootstrap.servers", bootstrapServer)
            .option("subscribe", topicName)
            .option("startingOffsets", "earliest")
            .option("numPartitions", 1)
            .load()

        val schema = StructType(List(
            StructField("payload", StructType(List(
                StructField("after", StructType(List(
                    StructField("purchase_detail_id", StringType),
                    StructField("amount", DoubleType),
                    StructField("quantity", LongType),
                    StructField("status", StringType),
                    StructField("last_updated_timestamp", StringType),
                    StructField("fk_item_id", LongType),
                    StructField("fk_purchase_id", LongType),
                    StructField("fk_warehouse_id", LongType),
                ))),
                StructField("op", StringType)
            ))),
        ))

        val valueDF = kafkaDF.select(from_json(col("value").cast("string"), schema).alias("value"))

        val purchaseDetailDF = valueDF.selectExpr("cast(value.payload.after.purchase_detail_id as string) as purchase_detail_id",
            "value.payload.after.amount as amount", "value.payload.after.quantity as quantity",
            "value.payload.after.status as status", "to_timestamp(value.payload.after.last_updated_timestamp) as timestamp",
            "cast(value.payload.after.fk_item_id as string) as item_id", "cast(value.payload.after.fk_purchase_id as string) as purchase_id",
            "cast(value.payload.after.fk_warehouse_id as string) as warehouse_id")

        import spark.implicits._
        val windowAggDF = purchaseDetailDF
            .withWatermark("timestamp", "1 seconds")
            .groupBy(window(col("timestamp"), "1 minute"))
            .agg(
                sum("quantity").alias("total_items"),
                sum("amount").alias("total_amount"),
                count(when($"status" === "ORDER_PLACED", 1)).as("Received Orders"),
                count(when($"status" === "ORDER_SHIPPED", 1)).as("Shipped Orders"),
                count(when($"status" === "ORDER_DELIVERED", 1)).as("Delivered Orders"),
            )

        val gmvDF = windowAggDF.select("window.start", "window.end", "total_items", "total_amount", "Received Orders", "Shipped Orders", "Delivered Orders")

        val reportStatusQuery = gmvDF.writeStream
            .format("csv")
            .option("checkpointLocation", "chk-point-dir-2")
            .option("header", "true")
            .trigger(Trigger.ProcessingTime("1 seconds"))
            .outputMode("append")
            .option("path", "csv_report_quantity_gmv")
            .queryName("Invoice Writer")
            .start()

        val kafkaTargetDF = purchaseDetailDF.selectExpr(
            """to_json(named_struct(
              |'schema', named_struct(
              |'type', 'struct',
              |'fields', array(
              |named_struct('field', 'tags',
              |'type', 'map',
              |'keys', named_struct(
              |'type', 'string',
              |'optional', false
              |),
              |'values', named_struct(
              |'type', 'string',
              |'optional', false
              |),
              |'optional', false
              |),
              |named_struct('field', 'amount',
              |'type', 'double',
              |'keys', named_struct(
              |'type', 'string',
              |'optional', false
              |),
              |'values', named_struct(
              |'type', 'double',
              |'optional', false
              |),
              |'optional', true
              |),
              |named_struct('field', 'quantity',
              |'type', 'double',
              |'keys', named_struct(
              |'type', 'string',
              |'optional', false
              |),
              |'values', named_struct(
              |'type', 'double',
              |'optional', false
              |),
              |'optional', true
              |)
              |),
              |'optional', false,
              |'version', 1
              |),
              |'payload', named_struct(
              |'tags', named_struct(
              |'timestamp', timestamp,
              |'purchase_detail_id', purchase_detail_id,
              |'purchase_id', purchase_id,
              |'item_id', item_id,
              |'status', status,
              |'warehouse_id', warehouse_id
              |),
              |'amount', amount,
              |'quantity', quantity
              |)
              |)) as value""".stripMargin)


        val itemStatusQuery = kafkaTargetDF
            .writeStream
            .queryName("Spark Writer")
            .format("kafka")
            .option("kafka.bootstrap.servers", "localhost:9092")
            .option("topic", "spark_stream")
            .outputMode("update")
            .option("checkpointLocation", "chk-point-dir-3")
            .start()

        spark.streams.awaitAnyTermination()

        spark.stop()
    }

}
