package com.gu.sf_datalake_export.salesforce_bulk_api

import com.gu.salesforce.SalesforceClient.{GetMethod, StringHttpRequest}
import com.gu.sf_datalake_export.salesforce_bulk_api.CreateJob.JobId
import com.gu.util.resthttp.HttpOp
import com.gu.util.resthttp.RestRequestMaker.{BodyAsString, RelativePath}
import com.gu.util.resthttp.Types.{ClientFailableOp, ClientFailure, ClientSuccess, GenericError}
import play.api.libs.json.{JsString, JsValue, Json, Writes}

import scala.xml.Elem

object GetJobBatches {

  case class BatchId(value: String) extends AnyVal

  object BatchId {
    implicit val writes = Json.writes[BatchId]
  }

  sealed trait BatchState {
    def name: String
  }

  object BatchState {
    val allStates = List(Queued, InProgress, Completed, Failed, NotProcessed)
    implicit val writes: Writes[BatchState] = (state: BatchState) => JsString(state.name)

    def fromStringState(state: String): ClientFailableOp[BatchState] = allStates.find(_.name == state).map {
      ClientSuccess(_)
    }.getOrElse(GenericError(s"unknown batch state: $state"))
  }

  object Queued extends BatchState {
    val name = "Queued"
  }

  object InProgress extends BatchState {
    val name = "InProgress"
  }

  object Completed extends BatchState {
    val name = "Completed"
  }

  object Failed extends BatchState {
    val name = "Failed"
  }

  object NotProcessed extends BatchState {
    val name = "NotProcessed"
  }

  case class BatchInfo(
    batchId: BatchId,
    status: BatchState
  )

  object BatchInfo {
    implicit val writes = Json.writes[BatchInfo]
  }

  def parseBatches(xml: Elem): ClientFailableOp[Seq[BatchInfo]] = {
    val batchInfos = (xml \ "batchInfo")
    val failableBatchInfos = batchInfos.map { batchInfo =>
      val batchId = BatchId((batchInfo \ "id").text)
      val batchStateStr = (batchInfo \ "state").text
      val batchState = BatchState.fromStringState(batchStateStr)
      batchState.map(BatchInfo(batchId, _))
    }
    val failures = failableBatchInfos.collect { case error: ClientFailure => error }
    if (failures.nonEmpty) {
      GenericError("errors returned : " + failures.mkString(";"))
    }
    val successes = failableBatchInfos.collect { case ClientSuccess(batchInfo) => batchInfo }
    ClientSuccess(successes)
  }

  def apply(post: HttpOp[StringHttpRequest, BodyAsString]): JobId => ClientFailableOp[Seq[BatchInfo]] =
    post.setupRequest[JobId] { jobId: JobId =>
      val relativePath = RelativePath(s"/services/async/44.0/job/${jobId.value}/batch")
      StringHttpRequest(relativePath, GetMethod)
    }.flatMap { response =>
      println("GOT RESPONSE")
      println(response.value)
      val xml: Elem = scala.xml.XML.loadString(response.value)
      parseBatches(xml)
    }.runRequest

}
