package org.apache.spark.ml

import com.cloudera.sparkts.models.{HOLTWinters, HOLTWintersModel}
import org.apache.spark.Logging
import org.apache.spark.ml.evaluation.TimeSeriesEvaluator
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.util.{DefaultParamsReadable, DefaultParamsWritable, Identifiable}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row

import scala.reflect.ClassTag

/**
  * Created by dirceu on 19/05/16.
  */
class HoltWintersBestModelFinder[L](override val uid: String)(implicit kt: ClassTag[L])
  extends HoltWintersBestModelEvaluation[L, HoltWintersModel[L]]
    with DefaultParamsWritable
    with TimeSeriesBestModelFinder
    with Logging {

  import org.apache.spark.sql.DataFrame

  def setTimeSeriesEvaluator(eval: TimeSeriesEvaluator[L]) = set(timeSeriesEvaluator, eval)

  def setEstimatorParamMaps(value: Array[ParamMap]): this.type = set(estimatorParamMaps, value)

  def setNFutures(value: Int) = set(nFutures, value)

  override def setValidationCol(value: String) = set(validationCol, value)

  def setFeaturesCol(label: String) = set(featuresCol, label)

  def setLabelCol(label: String) = set(labelCol, label)


  def this()(implicit kt: ClassTag[L]) = this(Identifiable.randomUID("arima"))

  def modelEvaluation(idModels: RDD[(L, Row, Option[HOLTWintersModel])]): RDD[(L, (HOLTWintersModel, ModelParamEvaluation[L]))] = {
    val eval = $(timeSeriesEvaluator)
    val broadcastEvaluator = idModels.context.broadcast(eval)
    idModels.filter(_._3.isDefined).map {
      case (id, row, models) =>
        val evaluatedModels = models.map {
          model =>
            holtWintersEvaluation(row, model, broadcastEvaluator, id)
        }.head
        log.warn(s"best model reach ${evaluatedModels._2.metricResult}")
        (id, evaluatedModels)
    }
  }

  override protected def train(dataSet: DataFrame): HoltWintersModel[L] = {
    val splitDs = split(dataSet, $(nFutures))
    val idModels = splitDs.rdd.map(train)
    new HoltWintersModel[L](uid, modelEvaluation(idModels)).setValidationCol($(validationCol)).
      asInstanceOf[HoltWintersModel[L]]
  }

  def train(row: Row): (L, Row, Option[HOLTWintersModel]) = {
    val id = row.getAs[L]($(labelCol))

    val result = try {
      Some(HOLTWinters.fitModel(row.getAs($(featuresCol)), $(nFutures)))
    } catch {
      case e: Exception =>
        log.error(s"Got the following Exception ${e.getLocalizedMessage} in id $id")
        None
    }
    (id, row, result)
  }
}

object HoltWintersBestModelFinder extends DefaultParamsReadable[HoltWintersBestModelFinder[_]] {

  override def load(path: String): HoltWintersBestModelFinder[_] = super.load(path)
}
