package my.kafka.wordcount

import java.util.HashMap

import org.apache.kafka.clients.producer.{ KafkaProducer, ProducerConfig, ProducerRecord }
import org.apache.spark.SparkConf
import org.apache.spark.streaming._
import org.apache.spark.streaming.kafka._
import org.apache.spark.streaming.{ Seconds, StreamingContext }
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.streaming._
import org.apache.spark.{ SparkContext, SparkConf }
import org.apache.spark.storage.StorageLevel

object KafkaWordCountCk {

  def createContext(zkQuorum: String, group: String, topics: String, numThreads: String, checkpointDirectory: String): StreamingContext = {

    // If you do not see this printed, that means the StreamingContext has been loaded
    // from the new checkpoint
    println("Creating new context")
    val conf = new SparkConf().setMaster("local[*]").setAppName("Spark Streaming - Kafka Producer - PopularHashTags").set("spark.executor.memory", "1g")

    conf.set("spark.streaming.receiver.writeAheadLog.enable", "true")

    val sc = new SparkContext(conf)

    sc.setLogLevel("WARN")

    // Set the Spark StreamingContext to create a DStream for every 2 seconds  
    val ssc = new StreamingContext(sc, Seconds(2))

    ssc.checkpoint(checkpointDirectory)

    // Map each topic to a thread  
    val topicMap = topics.split(",").map((_, numThreads.toInt)).toMap
    // Map value from the kafka message (k, v) pair      
    val lines = KafkaUtils.createStream(ssc, zkQuorum, group, topicMap).map(_._2)
    // Filter hashtags
    val hashTags = lines.flatMap(_.split(" "))

    // Get the top hashtags over the previous 60/10 sec window   
    val topCounts60 = hashTags.map((_, 1)).reduceByKeyAndWindow(_ + _, Seconds(60))
      .map { case (topic, count) => (count, topic) }
      .transform(_.sortByKey(false))

    val topCounts10 = hashTags.map((_, 1)).reduceByKeyAndWindow(_ + _, Seconds(10))
      .map { case (topic, count) => (count, topic) }
      .transform(_.sortByKey(false))
    
    // Get the top hashtags for current => after recover, data will be loaded once from checkpoint 
    val topCounts = hashTags.map((_, 1)).reduceByKey(_ + _)
      .map { case (topic, count) => (count, topic) }
      .transform(_.sortByKey(false))

    lines.print()

    // Print popular hashtags
    topCounts60.foreachRDD(rdd => {
      val topList = rdd.take(10)
      println("\nPopular topics in last 60 seconds (%s total):".format(rdd.count()))
      topList.foreach { case (count, tag) => println("%s (%s tweets)".format(tag, count)) }
    })

//    topCounts10.foreachRDD(rdd => {
//      val topList = rdd.take(10)
//      println("\nPopular topics in last 10 seconds (%s total):".format(rdd.count()))
//      topList.foreach { case (count, tag) => println("%s (%s tweets)".format(tag, count)) }
//    })
    
    topCounts.foreachRDD(rdd => {
      val topList = rdd.take(10)
      println("\nPopular topics (%s total):".format(rdd.count()))
      topList.foreach { case (count, tag) => println("%s (%s tweets)".format(tag, count)) }
    })

    lines.count().map(cnt => "Received " + cnt + " kafka messages.").print()

    ssc

  }

  def main(args: Array[String]) {
    
    if (args.length != 5) {
      System.err.println("Your arguments were " + args.mkString("[", ", ", "]"))
      System.err.println(
        """
          |Usage: KafkaWordCountCk <zkQuorum> <group> <topics>
          |     <numThreads> <checkpointDirectory> 
        """.stripMargin
      )
      System.exit(1)
    }

    // Create an array of arguments: zookeeper hostname/ip,consumer group, topicname, num of threads   
    val Array(zkQuorum, group, topics, numThreads, checkpointDirectory) = args

    val ssc = StreamingContext.getOrCreate(checkpointDirectory,
      () => createContext(zkQuorum, group, topics, numThreads, checkpointDirectory))
    ssc.sparkContext.setLogLevel("WARN")
    ssc.start()
    ssc.awaitTermination()
  }

}