package isr.project
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.{HTable, Put}
import org.apache.hadoop.hbase.util.Bytes
import org.apache.spark.rdd.RDD
/**
  * Created by Eric on 11/9/2016.
  */
object DataWriter {


  def writeTweets(tweetRDD: RDD[Tweet]): Unit = {

    //tweets.repartition(12)
    //tweets.cache()

    val _tableName: String = /*"cla-test-table"*/"ideal-cs5604f16"
    val _colFam : String = /*"cla-col-fam"*/"clean-tweet"
    val _col : String = /*"classification"*/"real-world-events"
    //implicit val config = HBaseConfig()
    //val headers = Seq(_col)
    //val rdd: RDD[(String, Seq[String])] = tweets.map({tweet => tweet.id -> Seq(labelMapper(tweet.label.getOrElse(999999.0)))})
    //rdd.toHBase(_tableName, _colFam, headers)
    val interactor = new HBaseInteraction(_tableName)
    tweetRDD.collect().foreach(tweet => interactor.putValueAt(_colFam, _col, tweet.id, labelMapper(tweet.label.getOrElse(9999999.0))))
    interactor.close()
/*    tweetRDD.foreachPartition(tweet => {
      val hbaseConf = HBaseConfiguration.create()
      val table = new HTable(hbaseConf, _tableName)
      tweet.map(tweet => writeTweetToDatabase(tweet, _colFam, _col, table)).foreach(x => table.put(x))
    })*/


    //val firstTweet = tweetRDD.take(1)
    //firstTweet.map(actualTweets =>
      //println(s"Tweet Text:${actualTweets.tweetText} Label:${actualTweets.label}"))
/*
    val writeTweets = tweetRDD.foreach(tweet => {
      val hbaseConf = HBaseConfiguration.create()
      val table = new HTable(hbaseConf, _tableName)
      val putAction = writeTweetToDatabase(tweet, _colFam, _col, table)
      table.put(putAction)
      table.close()
    })
*/
    /*tweetRDD.map(tweet => {
      val hbaseConf = HBaseConfiguration.create()

      val table = new HTable(hbaseConf,_tableName)
      writeTweetToDatabase(tweet, _colFam, _col, table)//.foreach(table.put)
    })*/
    //val interactor = new HBaseInteraction(_tableName)
    //tweets.collect.foreach(tweet => writeTweetToDatabase(tweet,interactor, _colFam, _col))
    //println("Wrote to database " + tweets.count() + " tweets")

 }
  def writeTrainingData(tweets: RDD[Tweet]): Unit = {
    val _tableName: String = "cs5604-f16-cla-training"
    val _textColFam: String = "training-tweet"
    val _labelCol: String = "label"
    val _textCol : String = "text"
    tweets.foreachPartition(
      tweetRDD => {
        val hbaseConf = HBaseConfiguration.create()
        val table = new HTable(hbaseConf,_tableName)
        tweetRDD.map(tweet => {
          val put = new Put(Bytes.toBytes(tweet.id))
          put.addColumn(Bytes.toBytes(_textColFam), Bytes.toBytes(_labelCol), Bytes.toBytes(tweet.label.get.toString))
          put.addColumn(Bytes.toBytes(_textColFam), Bytes.toBytes(_textCol), Bytes.toBytes(tweet.tweetText))
          put
        }).foreach(table.put)
      }
    )

  }

  def writeTweetToDatabase(tweet: Tweet, colFam: String, col: String, table: HTable): Put = {
    val putAction = putValueAt(colFam, col, tweet.id, labelMapper(tweet.label.getOrElse(9999999.0)), table)
    putAction
  }

  def putValueAt(columnFamily: String, column: String, rowKey: String, value: String, table: HTable) : Put = {
    // Make a new put object to handle adding data to the table
    // https://hbase.apache.org/apidocs/org/apache/hadoop/hbase/client/Put.html
    val put = new Put(Bytes.toBytes(rowKey))

    // add data to the put
    put.add(Bytes.toBytes(columnFamily), Bytes.toBytes(column), Bytes.toBytes(value))

    // put the data in the table
    put
  }


  def labelMapper(label:Double) : String= {
    val map = Map(9.0 -> "NewYorkFirefighterShooting", 1.0->"ChinaFactoryExplosion", 2.0->"KentuckyAccidentalChildShooting",
      3.0->"ManhattanBuildingExplosion", 4.0->"NewtownSchoolShooting", 5.0->"HurricaneSandy", 6.0->"HurricaneArthur",
      7.0->"HurricaneIsaac", 8.0->"TexasFertilizerExplosion", 10.0->"QuebecTrainDerailment", 11.0->"FairdaleTornado",
      12.0->"OklahomaTornado", 13.0->"MississippiTornado", 14.0->"AlabamaTornado")
    map.getOrElse(label,"CouldNotClassify")
  }
}

