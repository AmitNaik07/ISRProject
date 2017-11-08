/*


*/
package isr.project


import org.apache.hadoop.hbase.{HBaseConfiguration, TableName}
import org.apache.hadoop.hbase.filter.{FilterList, SingleColumnValueFilter}
//import org.apache.hadoop.hbase.CompareOperator
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp
import org.apache.hadoop.hbase.client.{ConnectionFactory, HTable, Result, Scan}
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
case class Tweet(id: String, tweetText: String, label: Option[Double] = None) //ID is the hbase table row key!

object DataRetriever {

	var _metaDataColFam : String = "metadata"
	var _metaDataTypeCol : String = "doc-type"
	var _metaDataCollectionNameCol : String = "collection-name"
	var _tweetColFam : String = "tweet"
	var _tweetUniqueIdCol : String = "tweet-id"
  var _cleanTweetColFam : String = "clean-tweet"
  var _cleanTweetTextCol : String = "clean-text-cla"

	var _classificationColFam = "classification"
	var _classCol : String = "classification-list"
	var _classProbCol : String = "probability-list"


  def retrieveTweets(collectionName:String, _cachedRecordCount:Int, tableNameSrc:String, tableNameDest:String, sc: SparkContext): RDD[Tweet] = {
    //implicit val config = HBaseConfig()
		var _lrModelFilename = "./data/" + collectionName + "_tweet_lr.model";
		var _word2VecModelFilename = "./data/" + collectionName + "_tweet_w2v.model";
		

    val bcWord2VecModelFilename = sc.broadcast(_word2VecModelFilename)
    val bcLRClassifierModelFilename = sc.broadcast(_lrModelFilename)
    val word2vecModel = Word2VecModel.load(sc, bcWord2VecModelFilename.value)
    val logisticRegressionModel = LogisticRegressionModel.load(sc, bcLRClassifierModelFilename.value)
    println(s"Classifier Model file found:$bcLRClassifierModelFilename. Loading model.")
    //Perform a cold start of the model pipeline so that this loading
    //doesn't disrupt the read later.
    val coldTweet = sc.parallelize(Array[Tweet]{ Tweet("id", "Some tweet")})
    val predictedTweets = Word2VecClassifier.predictClass(coldTweet, sc, word2vecModel, logisticRegressionModel)
    predictedTweets.count

    // scan over only the collection
    val scan = new Scan()

    val hbaseConf = HBaseConfiguration.create()
    val srcTable = new HTable(hbaseConf, tableNameSrc)
		val destTable = new HTable(hbaseConf, tableNameDest)

		if( !srcTable.getTableDescriptor().hasFamily(Bytes.toBytes(_metaDataColFam)) || !srcTable.getTableDescriptor().hasFamily(Bytes.toBytes(_tweetColFam)) ){
			System.err.println("ERROR: Source tweet table missing required column family!");
			return null;
		}

		if( !destTable.getTableDescriptor().hasFamily(Bytes.toBytes(_classificationColFam)) ){
			System.err.println("ERROR: Destination tweet table missing required classification column family!");
			return null;
		}

    // MUST scan the column to filter using it... else it assumes column does not exist and will auto filter if setFilterIfMissing(true) is set.
		scan.addColumn(Bytes.toBytes(_metaDataColFam), Bytes.toBytes(_metaDataCollectionNameCol));
		scan.addColumn(Bytes.toBytes(_metaDataColFam), Bytes.toBytes(_metaDataTypeCol));
		scan.addColumn(Bytes.toBytes(_tweetColFam), Bytes.toBytes(_tweetUniqueIdCol));
    scan.addColumn(Bytes.toBytes(_cleanTweetColFam), Bytes.toBytes(_cleanTweetTextCol));
		//will throw exception if table does not have classification family. If ommitted from scan, filter assumes unclassified and will keep the row
		if( srcTable.getTableDescriptor().hasFamily(Bytes.toBytes(_classificationColFam)) ){	
			scan.addColumn(Bytes.toBytes(_classificationColFam), Bytes.toBytes(_classCol));
		}

		//filter for only same collection, is tweet, has clean text, and not classified ... uncomment when table has the missing fields
		val filterList = new FilterList(FilterList.Operator.MUST_PASS_ALL);

		println("Filter: Keeping Collection Name == " + collectionName)
		val filterCollect = new SingleColumnValueFilter(Bytes.toBytes(_metaDataColFam), Bytes.toBytes(_metaDataCollectionNameCol), CompareOp.EQUAL , Bytes.toBytes(collectionName));
		filterCollect.setFilterIfMissing(true);	//filter all rows that do not have a collection name
		filterList.addFilter(filterCollect);

		println("Filter: Keeping Doc Type == tweet")
		val filterTweet = new SingleColumnValueFilter(Bytes.toBytes(_metaDataColFam), Bytes.toBytes(_metaDataTypeCol), CompareOp.EQUAL , Bytes.toBytes("tweet"));
		filterTweet.setFilterIfMissing(true);	//filter all rows that are not marked as tweets
		filterList.addFilter(filterTweet);

		println("Filter: Keeping Clean Text != ''")
		val filterNoClean = new SingleColumnValueFilter(Bytes.toBytes(_cleanTweetColFam), Bytes.toBytes(_cleanTweetTextCol), CompareOp.NOT_EQUAL , Bytes.toBytes(""));	//note compareOp vs compareOperator depending on hadoop version
		filterNoClean.setFilterIfMissing(true);	//filter all rows that do not have clean text column
		filterList.addFilter(filterNoClean);


		val filterUnclass = new SingleColumnValueFilter(Bytes.toBytes(_classificationColFam), Bytes.toBytes(_classCol), CompareOp.EQUAL , Bytes.toBytes(""));
		filterUnclass.setFilterIfMissing(false);	//keep only unclassified data
		filterList.addFilter(filterUnclass);
	
		scan.setFilter(filterList);


    // add caching to increase speed
    scan.setCaching(_cachedRecordCount)
    //scan.setBatch(100)
    val resultScanner = srcTable.getScanner(scan)

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
          rddT.repartition(1)
          //println("*********** Cleaning the tweets now. *****************")
          //val cleanTweets = CleanTweet.clean(rddT, sc)
          println("*********** Predicting the tweets now. *****************")
					println("IS EMPTY?: " + rddT.isEmpty().toString)
					rddT.collect().foreach(println)
          val predictedTweets = Word2VecClassifier.predictClass(rddT, sc, word2vecModel, logisticRegressionModel)
          println("*********** Persisting the tweets now. *****************")

          val repartitionedPredictions = predictedTweets.repartition(1)
          DataWriter.writeTweets(repartitionedPredictions, tableNameDest)

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



    /*val scanner = new Scan(Bytes.toBytes(collectionName), Bytes.toBytes(collectionName + '0'))
    val cols = Map(
      _colFam -> Set(_col)
    )*/
    //val rdd = sc.hbase[String](_tableName,cols,scanner)
    //val result  = interactor.getRowsBetweenPrefix(collectionName, _colFam, _col)
    //sc.parallelize(result.iterator().map(r => rowToTweetConverter(r)).toList)
    //rdd.map(v => Tweet(v._1, v._2.getOrElse(_colFam, Map()).getOrElse(_col, ""))).foreach(println)
    //rdd.map(v => Tweet(v._1, v._2.getOrElse(_colFam, Map()).getOrElse(_col, "")))/*.repartition(sc.defaultParallelism)*/.filter(tweet => tweet.tweetText.trim.isEmpty)
  }

  def rowToTweetConverter(result : Result): Tweet ={
		var _cleanTweetColFam : String = "clean-tweet"
  	var _cleanTweetTextCol : String = "clean-text-cla"
    val cell = result.getColumnLatestCell(Bytes.toBytes(_cleanTweetColFam), Bytes.toBytes(_cleanTweetTextCol))
    val key = Bytes.toString(result.getRow())
    val words = Bytes.toString(cell.getValueArray, cell.getValueOffset, cell.getValueLength)
		//println("T: " + words + " . key: " + key);
    Tweet(key,words)
		
  }

  def retrieveTrainingTweetsFromFile(fileName:String, sc : SparkContext) : RDD[Tweet] = {
    val lines = sc.textFile(fileName)
    lines.map(line=> Tweet(line.split('|')(1), line.split('|')(2), Option(line.split('|')(0).toDouble))).filter(tweet => tweet.label.isDefined)
  }

	//not conform to schema
  def getTrainingTweets(tableName:String, sc:SparkContext): RDD[Tweet] = {
    val _textColFam: String = "clean-tweet"
    val _labelCol: String = "classification-label"
    val _textCol : String = "clean-text-cla"
    val connection = ConnectionFactory.createConnection()
    val table = connection.getTable(TableName.valueOf(tableName))
    val scanner = new Scan()
    scanner.addColumn(Bytes.toBytes(_textColFam), Bytes.toBytes(_labelCol))
    scanner.addColumn(Bytes.toBytes(_textColFam), Bytes.toBytes(_textCol))
    sc.parallelize(table.getScanner(scanner).map(result => {
      val labcell = result.getColumnLatestCell(Bytes.toBytes(_textColFam), Bytes.toBytes(_labelCol))
      val textcell = result.getColumnLatestCell(Bytes.toBytes(_textColFam), Bytes.toBytes(_textCol))
      val key = Bytes.toString(labcell.getRowArray, labcell.getRowOffset, labcell.getRowLength)
      val words = Bytes.toString(textcell.getValueArray, textcell.getValueOffset, textcell.getValueLength)
      val label = Bytes.toString(labcell.getValueArray, labcell.getValueOffset, labcell.getValueLength).toDouble
      Tweet(key,words,Option(label))
    }).toList)
  }



}

