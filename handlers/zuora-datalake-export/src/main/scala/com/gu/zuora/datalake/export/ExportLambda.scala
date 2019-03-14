package com.gu.zuora.datalake.export

import java.sql.Date

import io.circe.generic.auto._
import io.circe.parser._
import io.github.mkotsur.aws.handler.Lambda._
import io.github.mkotsur.aws.handler.Lambda
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.s3.AmazonS3Client
import scalaj.http.Http

import scala.io.Source
import scala.concurrent.duration._

case class Oauth(clientId: String, clientSecret: String)
case class ZuoraDatalakeExport(oauth: Oauth)
case class Config(stage: String, baseUrl: String, zuoraDatalakeExport: ZuoraDatalakeExport)
case class AccessToken(access_token: String)
case class QueryResponse(id: String)
case class Batch(fileId: Option[String], batchId: String, status: String, name: String, message: Option[String])
case class JobResults(status: String, id: String, batches: List[Batch])

class ExportLambda extends Lambda[Option[String], String] {
  override def handle(incrementalDate: Option[String], context: Context) = {
    val jobId = StartAquaJob(incrementalDate)
    val status = GetJobResult(jobId)
    val batch = status.batches.head // FIXME: iterate when more than one query
    val csvFile = GetResultsFile(batch)
    SaveCsvToBucket(csvFile, batch)
    Right(s"Successfully exported Zuora to Datalake jobId = $jobId")
  }
}

sealed abstract case class Query(batchName: String, zoql: String, s3Bucket: String, s3Key: String)
object AccountQuery extends Query(
  "Account",
  "SELECT Account.Balance,Account.AutoPay,Account.Currency,Account.ID,Account.IdentityId__c,Account.LastInvoiceDate,Account.sfContactId__c,Account.MRR FROM Account WHERE Status != 'Canceled' AND (ProcessingAdvice__c != 'DoNotProcess' OR ProcessingAdvice__c IS NULL)",
  "ophan-temp-schema", // FIXME: Give proper ophan buckets
  "marioTest/raw/zuora/increment/Account.csv" // FIXME: Account.csv
)
object Query {
  def apply(batchName: String): Query = batchName match {
    case AccountQuery.batchName => AccountQuery
    case _ => throw new RuntimeException(s"Failed to create Query object due to unexpected batch name: $batchName")
  }
}

object ZuoraApiHost {
  def apply(): String = {
    System.getenv("Stage") match {
      case "CODE" => "https://rest.apisandbox.zuora.com"
      case "PROD" => "https://rest.zuora.com"
    }
  }
}

case class ZuoraAquaStatefulApi(
  version: String = "1.2",
  project: String = "zuora-datalake-export",
  partner: String
)
object ZuoraAquaStatefulApi {
  def apply(): ZuoraAquaStatefulApi = System.getenv("Stage") match {
    case "CODE" => ZuoraAquaStatefulApi(partner = "guardian-12357")
    case "PROD" => throw new NotImplementedError("Contact zuora support to request PROD AQuA Stateful partner ID")
  }
}

object ReadConfig {
  def apply(): Config = {
    val stage = System.getenv("Stage")
    val s3Client = AmazonS3Client.builder.build()
    val bucketName = "gu-reader-revenue-private"
    val key = s"membership/support-service-lambdas/$stage/zuoraRest-$stage.v1.json"
    val inputStream = s3Client.getObject(bucketName, key).getObjectContent
    val rawJson = Source.fromInputStream(inputStream).mkString
    decode[Config](rawJson) match {
      case Right(oauth) => oauth
      case Left(error) => throw new RuntimeException(s"Could not read secret config file from S3://$bucketName/$key", error)
    }
  }
}

object AccessToken {
  def apply(): String = {
    val config = ReadConfig()
    val oauthConfig = config.zuoraDatalakeExport.oauth
    val response = Http(s"${ZuoraApiHost()}/oauth/token")
      .postForm(Seq(
        "client_id" -> oauthConfig.clientId,
        "client_secret" -> oauthConfig.clientSecret,
        "grant_type" -> "client_credentials"
      ))
      .asString

    response.code match {
      case 200 => decode[AccessToken](response.body).getOrElse(throw new RuntimeException(s"Failed to decode oauth response: ${response}")).access_token
      case _ => throw new RuntimeException(s"Failed to generate oauth token: ${response}")
    }
  }
}

/**
 * https://knowledgecenter.zuora.com/DC_Developers/AB_Aggregate_Query_API/B_Submit_Query/e_Post_Query_with_Retrieval_Time
 */
object IncrementalTime {
  def apply(incrementalDate: Option[String]): String = incrementalDate match {
    case Some(date) =>
      s"""
           |"incrementalTime": "$date 00:00:00",
         """.stripMargin

    case None => ""
  }
}

/**
 * https://knowledgecenter.zuora.com/DC_Developers/AB_Aggregate_Query_API/BA_Stateless_and_Stateful_Modes#Automatic_Switch_Between_Full_Load_and_Incremental_Load
 */
object StartAquaJob {
  def apply(incrementalDate: Option[String]) = {
    val body =
      s"""
        |{
        |	"format" : "csv",
        |	"version" : "${ZuoraAquaStatefulApi().version}",
        |	"name" : "zuora-datalake-export",
        |	"encrypted" : "none",
        |	"useQueryLabels" : "true",
        |	"dateTimeUtc" : "true",
        |	"partner": "${ZuoraAquaStatefulApi().partner}",
        |	"project": "${ZuoraAquaStatefulApi().project}",
        |	${IncrementalTime(incrementalDate)}
        |	"queries" : [
        |		{
        |			"name" : "${AccountQuery.batchName}",
        |			"query" : "${AccountQuery.zoql}",
        |			"type" : "zoqlexport",
        |			"deleted" : {
        |                 "column" : "IsDeleted",
        |                 "format"  : "Boolean"
        |            }
        |		}
        |	]
        |}
      """.stripMargin

    val response = Http(s"${ZuoraApiHost()}/v1/batch-query/")
      .header("Authorization", s"Bearer ${AccessToken()}")
      .header("Content-Type", "application/json")
      .postData(body)
      .method("POST")
      .asString

    response.code match {
      case 200 => decode[QueryResponse](response.body).getOrElse(throw new RuntimeException(s"Failed to parse Query response: ${response}")).id
      case _ => throw new RuntimeException(s"Failed to start AQuA job: ${response}")
    }

  }
}
/**
 * Poll job status recursively until all queries have completed.
 *
 * https://knowledgecenter.zuora.com/DC_Developers/AB_Aggregate_Query_API/C_Get_Job_ID
 */
object GetJobResult {
  def apply(jobId: String): JobResults = {
    val response = Http(s"${ZuoraApiHost()}/v1/batch-query/jobs/$jobId")
      .header("Authorization", s"Bearer ${AccessToken()}")
      .header("Content-Type", "application/json")
      .asString

    response.code match {
      case 200 =>
        val jobResults = decode[JobResults](response.body).getOrElse(throw new RuntimeException(s"Failed to parse GetJobResult response: ${response}"))

        jobResults.batches.find(_.status == "aborted").map { abortedBatch =>
          throw new RuntimeException(s"Failed to complete query: $abortedBatch")
        }

        if (jobResults.batches.forall(_.status == "completed"))
          jobResults
        else {
          Thread.sleep(1.minute.toMillis)
          apply(jobId) // Keep trying until lambda timeout
        }
      case _ => throw new RuntimeException(s"Failed to execute request GetJobResult: $response")
    }
  }
}

/**
 * https://knowledgecenter.zuora.com/DC_Developers/AB_Aggregate_Query_API/D_Get_File_Download
 */
object GetResultsFile {
  def apply(batch: Batch) = {
    val fileId = batch.fileId.getOrElse(throw new RuntimeException("Failed to get csv file due to missing fileId"))
    val response = Http(s"${ZuoraApiHost()}/v1/file/$fileId")
      .header("Authorization", s"Bearer ${AccessToken()}")
      .asString

    response.code match {
      case 200 => response.body
      case _ => throw new RuntimeException(s"Failed to execute request GetResultsFile: ${response}")
    }
  }
}

object SaveCsvToBucket {
  def apply(csvContent: String, batch: Batch) = {
    val s3Client = AmazonS3Client.builder.build()
    System.getenv("Stage") match {
      case "CODE" => s3Client.putObject("zuora-datalake-export-code", Query(batch.name).s3Key, csvContent)
      case "PROD" => s3Client.putObject(Query(batch.name).s3Bucket, Query(batch.name).s3Key, csvContent)
    }
  }
}
