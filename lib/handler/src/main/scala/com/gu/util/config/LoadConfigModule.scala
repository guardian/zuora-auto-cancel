package com.gu.util.config

import com.amazonaws.services.s3.model.GetObjectRequest
import com.gu.util.Logging
import com.gu.util.config.ConfigReads.ConfigFailure
import play.api.libs.json._
import scalaz.{-\/, \/, \/-}

import scala.util.{Failure, Success, Try}

case class ConfigLocation[CONFIG](val path: String, version: Int)

case class ConfigWithStage(stage: String)

object ConfigWithStage {
  implicit val reads = Json.reads[ConfigWithStage]
}

object LoadConfigModule extends Logging {

  val bucketName = "gu-reader-revenue-private"

  //we need this extra class here because otherwise we cannot partially apply the LoadConfig apply method without specifying the generic param
  class PartialApply(stage: Stage, fetchString: GetObjectRequest => Try[String]) {
    def apply[CONF](implicit configLocation: ConfigLocation[CONF], reads: Reads[CONF]): ConfigFailure \/ CONF = {
      val basePath = s"membership/support-service-lambdas/${stage.value}"

      logger.info(s"Attempting to load config in $stage")
      val versionString = if (stage.value == "DEV") "" else s".v${configLocation.version}"
      val relativePath = s"${configLocation.path}-${stage.value}$versionString.json"
      val request = new GetObjectRequest(bucketName, s"$basePath/$relativePath")
      for {
        configStr <- toDisjunction(fetchString(request))
        jsValue <- toDisjunction(Try(Json.parse(configStr)))
        _ <- validateStage(jsValue, stage)
        config <- toDisjunction(Json.fromJson[CONF](jsValue))
      } yield config
    }
  }

  def apply(stage: Stage, fetchString: GetObjectRequest => Try[String]) = new PartialApply(stage = stage, fetchString = fetchString)

  def validateStage(jsValue: JsValue, expectedStage: Stage): ConfigFailure \/ Unit = {
    jsValue.validate[ConfigWithStage] match {
      case JsSuccess(ConfigWithStage(expectedStage.value), _) => \/-(())
      case JsSuccess(ConfigWithStage(otherStage), _) => -\/(ConfigFailure(s"Expected to load ${expectedStage.value} config, but loaded $otherStage config"))
      case JsError(error) => -\/(ConfigFailure(s"could not parse stage in configuration file: ${error}"))
    }
  }

  def toDisjunction[A](t: Try[A]) = t match {
    case Success(s) => \/-(s)
    case Failure(e) =>
      logger.error("error parsing json", e)
      -\/(ConfigFailure(e.toString))
  }

  def toDisjunction[A](jsResult: JsResult[A]) = jsResult match {
    case JsSuccess(jsValue, _) => \/-(jsValue)
    case JsError(error) =>
      logger.error(s"error parsing json $error")
      -\/(ConfigFailure(s"error parsing json : $error"))
  }
}
