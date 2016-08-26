/*
* Copyright 2015 eleflow.com.br.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package eleflow.uberdata

import java.sql.Timestamp

import eleflow.uberdata.core.exception.UnexpectedValueException
import eleflow.uberdata.enums.SupportedAlgorithm._
import org.apache.spark.Logging
import org.apache.spark.ml._
import org.apache.spark.ml.evaluation.TimeSeriesEvaluator
import org.apache.spark.ml.feature.{StringIndexer, VectorAssembler}
import org.apache.spark.ml.tuning.ParamGridBuilder
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row}

import scala.reflect.ClassTag

/**
	* Created by celio on 11/04/16.
	*/
object ForecastPredictor {
	def apply(): ForecastPredictor = new ForecastPredictor
}

class ForecastPredictor extends Serializable with Logging {

	lazy val defaultRange = (0 to 2).toArray

	trait TimestampOrd extends Ordering[Timestamp] {
		override def compare(x: Timestamp, y: Timestamp): Int = if (x.getTime < y.getTime) -1
		else if (x.getTime == y.getTime) 0
		else 1
	}

	implicit object TimestampOrdering extends TimestampOrd

	protected def defaultARIMAParamMap[T <: ArimaParams](estimator: T, paramRange: Array[Int]) = new ParamGridBuilder()
		.addGrid(estimator.arimaP, paramRange)
		.addGrid(estimator.arimaQ, paramRange)
		.addGrid(estimator.arimaD, paramRange)
		.build().filter(f => f.get[Int](estimator.arimaP).getOrElse(0) != 0 ||
		f.get[Int](estimator.arimaQ).getOrElse(0) != 0)

	def prepareARIMAPipeline[L](labelCol: String = "label", featuresCol: String = "features",
															validationCol: String = "validation", timeCol: String = "Date", nFutures: Int,
															paramRange: Array[Int] = defaultRange)(implicit kt: ClassTag[L]): Pipeline = {

		val transformer = createTimeSeriesGenerator[L](labelCol, featuresCol, timeCol)
		prepareARIMAPipelineInt[L](labelCol, featuresCol, validationCol, nFutures, paramRange, Array(transformer))
	}

	protected def prepareARIMAPipelineInt[L](labelCol: String, featuresCol: String, validationCol: String, nFutures: Int
																					 , paramRange: Array[Int], transformer: Array[Transformer])
																					(implicit kt: ClassTag[L]) = {
		val timeSeriesEvaluator: TimeSeriesEvaluator[L] = new TimeSeriesEvaluator[L]()
			.setValidationCol(validationCol)
			.setFeaturesCol(featuresCol)
			.setMetricName("rmspe")
		val arima = new ArimaBestModelFinder[L]()
			.setTimeSeriesEvaluator(timeSeriesEvaluator)
			.setLabelCol(labelCol)
			.setValidationCol(validationCol)
			.setNFutures(nFutures)
		val paramGrid = defaultARIMAParamMap[ArimaBestModelFinder[L]](arima, paramRange)
		val arimaBestModelFinder: ArimaBestModelFinder[L] = arima.setEstimatorParamMaps(paramGrid)
		preparePipeline(arimaBestModelFinder, preTransformers = transformer)
	}

	def prepareHOLTWintersPipeline[T](labelCol: String = "label", featuresCol: String = "features",
																		validationCol: String = "validation", timeCol: String = "Date", nFutures: Int = 6)
																	 (implicit kt: ClassTag[T]): Pipeline = {
		val transformer = createTimeSeriesGenerator(labelCol, featuresCol, timeCol)
		val timeSeriesEvaluator: TimeSeriesEvaluator[T] = new TimeSeriesEvaluator[T]()
			.setValidationCol(validationCol)
			.setFeaturesCol(featuresCol)
			.setMetricName("rmspe")
		val holtWinters = new HoltWintersBestModelFinder[T]()
			.setTimeSeriesEvaluator(timeSeriesEvaluator)
			.setLabelCol(labelCol)
			.setValidationCol(validationCol)
			.setNFutures(nFutures)
			.asInstanceOf[HoltWintersBestModelFinder[Double]]

		preparePipeline(holtWinters, preTransformers = Array(transformer))
	}

	def prepareMovingAveragePipeline[L](labelCol: String = "label", featuresCol: String = "features",
																			validationCol: String = "validation", timeCol: String = "Date", windowSize: Int = 8)
																		 (implicit kt: ClassTag[L]): Pipeline = {
		val transformer = createTimeSeriesGenerator(labelCol, featuresCol, timeCol)
		val movingAverage = new MovingAverage[L]()
			.setLabelCol(labelCol)
			.setOutputCol(validationCol)
			.setInputCol(featuresCol)
			.setWindowSize(windowSize)

		new Pipeline()
			.setStages(Array(transformer, movingAverage))
	}

	private def createTimeSeriesGenerator[L](labelCol: String, featuresCol: String, timeCol: String)
																					(implicit kt: ClassTag[L]): TimeSeriesGenerator[L] = {
		new TimeSeriesGenerator[L]()
			.setFeaturesCol(featuresCol)
			.setLabelCol(labelCol)
			.setTimeCol(timeCol)
			.setOutputCol("features")
	}

	private def preparePipeline(timeSeriesBestModelFinder: TimeSeriesBestModelFinder,
															preTransformers: Array[_ <: Transformer]): Pipeline = {

		new Pipeline()
			.setStages(preTransformers ++ Array(timeSeriesBestModelFinder))
	}

	def prepareBestForecastPipeline[L](labelCol: String, featuresCol: String, validationCol: String, timeCol: String,
																		 nFutures: Int, meanAverageWindowSize: Seq[Int], paramRange: Array[Int])
																		(implicit kt: ClassTag[L]): Pipeline = {
		val transformer = createTimeSeriesGenerator[L](labelCol, featuresCol, timeCol)
		val timeSeriesEvaluator: TimeSeriesEvaluator[L] = new TimeSeriesEvaluator[L]()
			.setValidationCol(validationCol)
			.setFeaturesCol(featuresCol)
			.setMetricName("rmspe")
		val findBestForecast = new ForecastBestModelFinder[L, ForecastBestModel[L]]
			.setWindowParams(meanAverageWindowSize)
			.setTimeSeriesEvaluator(timeSeriesEvaluator)
			.setLabelCol(labelCol)
			.setValidationCol(validationCol)
			.setNFutures(nFutures)
			.asInstanceOf[ForecastBestModelFinder[L, ForecastBestModel[L]]]
		val paramGrid = defaultARIMAParamMap[ForecastBestModelFinder[L, ForecastBestModel[L]]](findBestForecast, paramRange)
		findBestForecast.setEstimatorParamMaps(paramGrid)
		preparePipeline(findBestForecast, Array(transformer))
	}

	def prepareXGBoostSmalModel[L, G](labelCol: String, featuresCol: Seq[String], validationCol: String,
																		timeCol: String, idCol: String, groupByCol: String, schema: StructType)
																	 (implicit kl: ClassTag[L], kg: ClassTag[G]): Pipeline = {
		val timeSeriesEvaluator: TimeSeriesEvaluator[L] = new TimeSeriesEvaluator[L]()
			.setValidationCol(validationCol)
			.setLabelCol(labelCol)
			.setMetricName("rmspe")
		val xgboost = new XGBoostSmallModelBestModelFinder[L, G]()
			.setTimeSeriesEvaluator(timeSeriesEvaluator)
			.setLabelCol(labelCol)
			.setGroupByCol(groupByCol)
			.setIdCol(idCol)
			.setValidationCol(validationCol)

		new Pipeline()
			.setStages(smallModelPipelineStages(labelCol, featuresCol, timeCol, groupByCol, Some(idCol),
				schema = schema)
				:+ xgboost)
	}

	def smallModelPipelineStages(labelCol: String, featuresCol: Seq[String], timeCol: String,
    groupByCol: String, idCol: Option[String] = None, schema: StructType): Array[PipelineStage] =	{
		val allColumns = schema.map(_.name).toArray
		val stringColumns = schema
			.filter(f => f.dataType.isInstanceOf[StringType] && featuresCol.contains(f.name))
			.map(_.name)

		val nonStringColumns = allColumns.filter(f => !stringColumns.contains(f)
			&& featuresCol.contains(f))

		val stringIndexers = stringColumns.map { column =>
			new StringIndexer()
				.setInputCol(column)
				.setOutputCol(s"${column}Index")
		}.toArray

		val nonStringIndex = "nonStringIndex"
		val columnIndexers = new VectorizeEncoder()
			.setInputCol(nonStringColumns)
			.setOutputCol(nonStringIndex)
			.setLabelCol(labelCol)
			.setGroupByCol(groupByCol)
			.setIdCol(idCol.getOrElse(""))

		val assembler = new VectorAssembler()
			.setInputCols(stringColumns.map(f => s"${f}Index").toArray :+ nonStringIndex)
			.setOutputCol(IUberdataForecastUtil.FEATURES_COL_NAME)

		stringIndexers :+ columnIndexers :+ assembler
	}

	case class ColumnConfig()

	//label, GroupBy
	def predict[L, G](train: DataFrame, test: DataFrame, labelCol: String,
										featuresCol: Seq[String] = Seq.empty[String], timeCol: String, idCol: String,
										groupByCol: String, algorithm: Algorithm = FindBestForecast, nFutures: Int = 6,
										meanAverageWindowSize: Seq[Int] = Seq(8, 16, 26),
										paramRange: Array[Int] = defaultRange)(implicit kt: ClassTag[L],
																													 ord: Ordering[L] = null, gt: ClassTag[G])
	: (DataFrame, PipelineModel) = {
		require(featuresCol.nonEmpty, "featuresCol parameter can't be empty")
		val validationCol = idCol + algorithm.toString
		algorithm match {
			case Arima | HoltWinters | MovingAverage8 | MovingAverage16 | MovingAverage26
					 | FindBestForecast =>
				predictSmallModelFuture[L](train, test, labelCol, featuresCol.head, timeCol, idCol,
					algorithm, validationCol, nFutures, meanAverageWindowSize, paramRange)
			case XGBoostAlgorithm =>
				predictSmallModelFeatureBased[L, G](train, test, labelCol, featuresCol, timeCol, idCol,
					groupByCol, algorithm, validationCol)
			case _ => throw new UnexpectedValueException(s"Algorithm $algorithm can't be used to " +
				s"predict a Forecast")
		}
	}

	def predictSmallModelFeatureBased[L, G](train: DataFrame, test: DataFrame, labelCol: String, featuresCol: Seq[String],
																					timeCol: String, idCol: String, groupByCol: String, algorithm: Algorithm = XGBoostAlgorithm,
																					validationCol: String)(implicit kt: ClassTag[L], ord: Ordering[L] = null
																																 , gt: ClassTag[G]): (DataFrame, PipelineModel) = {
		require(algorithm == XGBoostAlgorithm, "The accepted algorithm for this method is XGBoostAlgorithm")
		val pipeline = prepareXGBoostSmalModel[L, G](labelCol, featuresCol, validationCol, timeCol, idCol, groupByCol,
			train.schema)
		val cachedTrain = train.cache
		val cachedTest = test.cache()
		val model = pipeline.fit(cachedTrain)
		val result = model.transform(cachedTest).cache
		val joined = result.select(idCol, IUberdataForecastUtil.FEATURES_PREDICTION_COL_NAME)
		val dfToBeReturned = joined.withColumnRenamed("featuresPrediction", "prediction")

		(dfToBeReturned.sort(idCol), model)
	}

	def predictSmallModelFuture[L](train: DataFrame, test: DataFrame, labelCol: String,
																 featuresCol: String, timeCol: String,
																 idCol: String, algorithm: Algorithm = FindBestForecast,
																 validationCol: String, nFutures: Int = 6,
																 meanAverageWindowSize: Seq[Int] = Seq(8, 16, 26),
																 paramRange: Array[Int] = defaultRange)
																(implicit kt: ClassTag[L], ord: Ordering[L] = null):
	(DataFrame, PipelineModel) = {
		require(algorithm != XGBoostAlgorithm, "The accepted algorithms for this method doesn't include XGBoost")
		val pipeline = algorithm match {
			case Arima =>
				prepareARIMAPipeline[L](labelCol, featuresCol, validationCol, timeCol, nFutures, paramRange)
			case HoltWinters => prepareHOLTWintersPipeline[L](labelCol, featuresCol, validationCol, timeCol, nFutures)
			case MovingAverage8 => prepareMovingAveragePipeline[L](labelCol, featuresCol, validationCol, timeCol, 8)
			case MovingAverage16 => prepareMovingAveragePipeline[L](labelCol, featuresCol, validationCol, timeCol, 16)
			case MovingAverage26 => prepareMovingAveragePipeline[L](labelCol, featuresCol, validationCol, timeCol, 26)
			case FindBestForecast => prepareBestForecastPipeline[L](labelCol, featuresCol, validationCol, timeCol,
				nFutures, meanAverageWindowSize, paramRange)
			case _ => throw new UnexpectedValueException(s"Algorithm $algorithm can't be used to predict a Forecast")
		}
		val cachedTrain = train.cache
		val model = pipeline.fit(cachedTrain)
		val result = model.transform(cachedTrain)
		val timeColIndex = test.columns.indexOf(timeCol)
		val sparkContext = train.sqlContext.sparkContext
		val timeColIndexBc = sparkContext.broadcast(timeColIndex)
		val labelColBc = sparkContext.broadcast(labelCol)
		val validationColBc = sparkContext.broadcast(validationCol)
		val validationColIndexBc = sparkContext.broadcast(result.columns.indexOf(validationCol))
		val labelColIndexBc = sparkContext.broadcast(result.columns.indexOf(labelCol))
		val featuresColIndexBc = sparkContext.broadcast(result.columns.indexOf("features"))
		val featuresValidationColIndexBc = sparkContext.broadcast(result.columns.indexOf("featuresValidation"))
		val groupedTest = test.rdd.groupBy(row => row.getAs[L](labelColBc.value)).map { case (key, values) =>
			val sort = values.toArray.map {
				row => IUberdataForecastUtil.convertColumnToLongAddAtEnd(row, timeColIndexBc.value)
			}.sortBy(row => row.getAs[Long](row.size - 1))
			(key, sort)
		}.cache
		val keyValueResult = result.rdd.map(row =>
			(row.getAs[L](labelColBc.value), (row.getAs[org.apache.spark.mllib.linalg.Vector](validationColBc.value).toArray,
				row))).cache
		val forecastResult = keyValueResult.join(groupedTest).flatMap {
			case (key, ((predictions, row), ids)) =>
				val filteredRow = row.schema.zipWithIndex.filter { case (value, index) => index != validationColIndexBc.value &&
					index != labelColIndexBc.value && index != featuresColIndexBc.value &&
					index != featuresValidationColIndexBc.value && value.name != "featuresPrediction"
				}
				ids.zip(predictions).map {
					case (id, prediction) =>
						val seq = id.toSeq
						val (used, _) = seq.splitAt(seq.length - 1)
						Row(used ++ filteredRow.map { case (_, index) => row.get(index) } :+ Math.round(prediction): _*)
				}
		}
		val sqlContext = train.sqlContext
		val schema = result.schema.fields.filter(f => f.name != validationCol && f.name != labelCol && f.name != "features"
			&& f.name != "featuresValidation" && f.name != "featuresPrediction").foldLeft(test.schema) {
			case (testSchema, field) => testSchema.add(field)
		}.add(StructField("prediction", LongType))
		val df = sqlContext.createDataFrame(forecastResult, schema)
		(df.sort(idCol), model)
	}

	def saveResult[T](toBeSaved: RDD[(T, Long)], path: String): Unit = {
		toBeSaved.map {
			case (key, value) => s"$key,$value"
		}.coalesce(1).saveAsTextFile(path)
	}

//	def predictBigModelFuture(train: DataFrame, test: DataFrame, algorithm: Algorithm): Unit = {
//		algorithm match {
//			case XGBoostAlgorithm => prepareXGBoostBigModel()
//			case LinearRegression =>
//			case _ => throw new UnsupportedOperationException()
//		}
//	}

//	def prepareXGBoostBigModel[L](labelCol:String): Pipeline = {
//		val validationCol:String = "validation"
//		val timeSeriesEvaluator: TimeSeriesEvaluator[L] = new TimeSeriesEvaluator[L]()
//			.setValidationCol(validationCol)
//			.setLabelCol(labelCol)
//			.setMetricName("rmspe")
//		val xgboost = new XGBoostSmallModelBestModelFinder[L, G]()
//			.setTimeSeriesEvaluator(timeSeriesEvaluator)
//			.setLabelCol(labelCol)
//			.setGroupByCol(groupByCol)
//			.setIdCol(idCol)
//			.setValidationCol(validationCol)
//
//		new Pipeline()
//			.setStages(smallModelPipelineStages(labelCol, featuresCol, timeCol, groupByCol, Some(idCol),
//				schema = schema)
//				:+ xgboost)
//	}
}
