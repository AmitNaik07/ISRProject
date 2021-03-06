package isr.project


import org.apache.hadoop.filter.CompareFilter.CompareOp
import org.apache.hadoop.hbase.{HBaseConfiguration, TableName}
import org.apache.hadoop.hbase.client.{ConnectionFactory, HTable, Result, Scan}
import org.apache.hadoop.hbase.filter.{RandomRowFilter, FilterList, SingleColumnValueFilter}
import org.apache.hadoop.hbase.util.Bytes
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.spark.mllib.classification.LogisticRegressionModel
import org.apache.spark.mllib.feature.Word2VecModel
import org.apache.spark.mllib.linalg.Word2VecClassifier

import scala.collection.JavaConversions._
/**
  * Created by Eric on 11/8/2016.
  */
case class Tweet(id: String, tweetText: String, label: Option[Double] = None)
object DataRetriever {
  val _lrModelFilename = "data/lrclassifier.model"
  var _cachedRecordCount = 50
  var _tableName: String = "ideal-cs5604f16" /*"ideal-cs5604f16-fake"*/
  var _columnFamily : String = "clean-tweet"
  var _Column : String = "clean-text-cla" /*"text"*/
  var _word2VecModelFilename = "data/word2vec.model"

  def retrieveTweets(args: Array[String], sc: SparkContext): RDD[Tweet] = {
    //implicit val config = HBaseConfig()

    // parse the collection ID from program arguments
    val collectionID = args(0)
    if (args.length >= 2)
      _cachedRecordCount = args(2).toInt


    val bcWord2VecModelFilename = sc.broadcast(_word2VecModelFilename)
    val bcLRClassifierModelFilename = sc.broadcast(_lrModelFilename)
    val word2vecModel = Word2VecModel.load(sc, bcWord2VecModelFilename.value)
    val logisticRegressionModel = LogisticRegressionModel.load(sc, bcLRClassifierModelFilename.value)
    println(s"Classifier Model file found:$bcLRClassifierModelFilename. Loading model.")
    //Perform a cold start of the model pipeline so that this loading
    //doesn't disrupt the read later.
    val coldTweet = sc.parallelize(Array[Tweet]{ Tweet("id", "Some tweet")})
    val (predictedTweets,_) = Word2VecClassifier.predict(coldTweet, sc, word2vecModel, logisticRegressionModel)
    predictedTweets.count

    // scan over only the collection
    val scan = new Scan(Bytes.toBytes(collectionID), Bytes.toBytes(collectionID + '0'))
    val hbaseConf = HBaseConfiguration.create()
    val table = new HTable(hbaseConf,_tableName)
    // add the specific column to scan
    scan.addColumn(Bytes.toBytes(_columnFamily), Bytes.toBytes(_Column))
    // add caching to increase speed
    scan.setCaching(_cachedRecordCount)
    scan.setBatch(100)
    val resultScanner = table.getScanner(scan)
    println(s"Caching Info:${scan.getCaching} Batch Info: ${scan.getBatch}")
    println("Scanning results now.")

    var continueLoop = true
    var totalRecordCount: Long = 0
    while (continueLoop) {
      try {
        println("Getting next batch of results now.")
        val start = System.currentTimeMillis()

        val results = resultScanner.next(_cachedRecordCount)

        if (results == null || results.isEmpty)
          continueLoop = false
        else {
          println(s"Result Length:${results.length}")
          val resultTweets = results.map(r => rowToTweetConverter(r))
          val rddT = sc.parallelize(resultTweets)
          rddT.cache()
          rddT.repartition(12)
          println("*********** Cleaning the tweets now. *****************")
          val cleanTweets = CleanTweet.clean(rddT, sc)
          println("*********** Predicting the tweets now. *****************")
          val (predictedTweets,_) = Word2VecClassifier.predict(cleanTweets, sc, word2vecModel, logisticRegressionModel)
          println("*********** Persisting the tweets now. *****************")

          val repartitionedPredictions = predictedTweets.repartition(12)
          DataWriter.writeTweets(repartitionedPredictions)

          predictedTweets.cache()
          val batchTweetCount = predictedTweets.count()
          println(s"The amount of tweets to be written is $batchTweetCount")
          val end = System.currentTimeMillis()
          totalRecordCount += batchTweetCount
          println(s"Took ${(end-start)/1000.0} seconds for This Batch.")
          println(s"This batch had $batchTweetCount tweets. We have processed $totalRecordCount tweets overall")
        }
      }
      catch {
        case e: Exception =>
          println(e.printStackTrace())
          println("Exception Encountered")
          println(e.getMessage)
          continueLoop = false
      }

    }

    println(s"Total record count:$totalRecordCount")
    resultScanner.close()
    //val interactor = new HBaseInteraction(_tableName)

    return null



    /*val scanner = new Scan(Bytes.toBytes(collectionID), Bytes.toBytes(collectionID + '0'))
    val cols = Map(
      _colFam -> Set(_col)
    )*/
    //val rdd = sc.hbase[String](_tableName,cols,scanner)
    //val result  = interactor.getRowsBetweenPrefix(collectionID, _colFam, _col)
    //sc.parallelize(result.iterator().map(r => rowToTweetConverter(r)).toList)
    //rdd.map(v => Tweet(v._1, v._2.getOrElse(_colFam, Map()).getOrElse(_col, ""))).foreach(println)
    //rdd.map(v => Tweet(v._1, v._2.getOrElse(_colFam, Map()).getOrElse(_col, "")))/*.repartition(sc.defaultParallelism)*/.filter(tweet => tweet.tweetText.trim.isEmpty)
  }

  def rowToTweetConverter(result : Result): Tweet ={
    val cell = result.getColumnLatestCell(Bytes.toBytes(_columnFamily), Bytes.toBytes(_Column))
    val key = Bytes.toString(cell.getRowArray, cell.getRowOffset, cell.getRowLength)
    val words = Bytes.toString(cell.getValueArray, cell.getValueOffset, cell.getValueLength)
    Tweet(key,words)
    null
  }

  def retrieveTrainingTweetsFromFile(fileName:String, sc : SparkContext) : RDD[Tweet] = {
    val lines = sc.textFile(fileName)
    lines.map(line=> Tweet(line.split('|')(1), line.split('|')(2), Option(line.split('|')(0).toDouble))).filter(tweet => tweet.label.isDefined)
  }

  def getTrainingTweets(collectionName:String, sc : SparkContext): RDD[Tweet] = {
    val _tableName: String =        "training_table"
    val _cleanTweetColFam: String = "clean-tweet"
	val _tweetColFam : String =     "tweet"
    val _metaDataColFam : String =  "metadata"

    val _cleanTweetCol : String =   "clean-text-cla"
	val _tweetCol : String =        "text"
    val _peopleCol : String =       "sner-people"
    val _locationsCol : String =    "sner-locations"
    val _orgCol : String =          "sner-organizations"  
    val _hashtagsCol : String =     "hashtags"
    val _longurlCol : String =      "long-url"
    val _collectionIDCol : String = "collection-id"
    
    val connection = ConnectionFactory.createConnection()
    val table = connection.getTable(TableName.valueOf(_tableName))
    val scanner = new Scan()
    
	//scanner.setMaxResultSize(250)

	scanner.addColumn(Bytes.toBytes(_tweetColFam),Bytes.toBytes(_tweetCol))
    scanner.addColumn(Bytes.toBytes(_cleanTweetColFam), Bytes.toBytes(_cleanTweetCol))
    scanner.addColumn(Bytes.toBytes(_cleanTweetColFam), Bytes.toBytes(_peopleCol))
    scanner.addColumn(Bytes.toBytes(_cleanTweetColFam), Bytes.toBytes(_locationsCol))
    scanner.addColumn(Bytes.toBytes(_cleanTweetColFam), Bytes.toBytes(_orgCol))
    scanner.addColumn(Bytes.toBytes(_cleanTweetColFam), Bytes.toBytes(_hashtagsCol))
    scanner.addColumn(Bytes.toBytes(_cleanTweetColFam), Bytes.toBytes(_longurlCol))
    scanner.addColumn(Bytes.toBytes(_metaDataColFam), Bytes.toBytes(_collectionIDCol))
    
    
    val filterList = new FilterList(FilterList.Operator.MUST_PASS_ALL)
    val filterCollect = new SingleColumnValueFilter(Bytes.toBytes(_metaDataColFam), Bytes.toBytes(_collectionIDCol), CompareOp.EQUAL, Bytes.toBytes(collectionName))
    filterCollect.setFilterIfMissing(true)
    filterList.addFilter(filterCollect)
    
    scan.setFilter(filterList)

    sc.parallelize(table.getScanner(scanner).map(result => {
      val textcell = result.getColumnLatestCell(Bytes.toBytes(_cleanTweetColFam), Bytes.toBytes(_cleanTweetCol))
      val rawcell = result.getColumnLatestCell(Bytes.toBytes(_tweetColFam), Bytes.toBytes(_tweetCol))
      val peoplecell = result.getColumnLatestCell(Bytes.toBytes(_cleanTweetColFam), Bytes.toBytes(_peopleCol))
      val locationcell = result.getColumnLatestCell(Bytes.toBytes(_cleanTweetColFam), Bytes.toBytes(_locationsCol))
      val orgcell = result.getColumnLatestCell(Bytes.toBytes(_cleanTweetColFam), Bytes.toBytes(_orgCol))
      val hashtagcell = result.getColumnLatestCell(Bytes.toBytes(_cleanTweetColFam), Bytes.toBytes(_hashtagsCol))
      val longurlcell = result.getColumnLatestCell(Bytes.toBytes(_cleanTweetColFam), Bytes.toBytes(_longurlCol))
      
      

      val words = Bytes.toString(textcell.getValueArray, textcell.getValueOffset, textcell.getValueLength)
      val people = Bytes.toString(peoplecell.getValueArray, peoplecell.getValueOffset, peoplecell.getValueLength)
      val locations = Bytes.toString(locationcell.getValueArray, locationcell.getValueOffset, locationcell.getValueLength)
      val orgs = Bytes.toString(orgcell.getValueArray, orgcell.getValueOffset, orgcell.getValueLength)
      val hashtags = Bytes.toString(hashtagcell.getValueArray, hashtagcell.getValueOffset, hashtagcell.getValueLength)
      val longurl = Bytes.toString(longurlcell.getValueArray, longurlcell.getValueOffset, longurlcell.getValueLength)
      
      val combinewords = words + " " + people + " " + locations + " " + orgs + " " + hashtags + " " + longurl

      //Remove basic stuffs 
      combinewords = combinewords.replaceAll("[@#]","")

      //Remove html stuffs
      combinewords = combinewords.replaceAll( """<(?!\/?a(?=>|\s.*>))\/?.*?>""", "")

      val rawwords = Bytes.toString(rawcell.getValueArray, rawcell.getValueOffset, rawcell.getValueLength)
      var key = Bytes.toString(result.getRow())
      println("Label this tweetID: " + key + " | RAW: "+rawwords)
      val label = Console.readInt().toDouble
      Tweet(key, combinewords, Option(label))
    }).toList)
  }
}

