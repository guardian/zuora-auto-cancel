package com.gu.autoCancel

import com.gu.autoCancel.ZuoraModels._
import com.gu.autoCancel.ZuoraReaders._
import com.gu.autoCancel.ZuoraWriters._
import java.util.concurrent.TimeUnit
import okhttp3._
import org.joda.time.LocalDate
import play.api.libs.json._
import scalaz.Scalaz._
import scalaz.\/

case class ZuoraRestConfig(baseUrl: String, username: String, password: String)

class ZuoraRestService(config: ZuoraRestConfig) extends Logging {

  val restClient = new OkHttpClient().newBuilder()
    .readTimeout(30, TimeUnit.SECONDS) // Not customer-facing so being generous with the http timeout
    .build()

  def buildRequest(config: ZuoraRestConfig, route: String): Request.Builder = {
    new Request.Builder()
      .addHeader("apiSecretAccessKey", config.password)
      .addHeader("apiAccessKeyId", config.username)
      .url(s"${config.baseUrl}/$route")
  }

  def convertResponseToCaseClass[T](response: Response)(implicit r: Reads[T]): String \/ T = {
    if (response.isSuccessful) {
      Json.parse(response.body.string).validate[T] match {
        case success: JsSuccess[T] => success.get.right
        case error: JsError => "Error when converting Zuora response to case class".left
      }
    } else {
      logger.error(s"Request to Zuora was unsuccessful, the response was: \n $response")
      (s"Request to Zuora was unsuccessful").left
    }
  }

  def getAccountSummary(accountId: String): String \/ AccountSummary = {
    logger.info(s"Getting account summary from Zuora for Account Id: $accountId")
    val request = buildRequest(config, s"accounts/$accountId/summary").get().build()
    val call = restClient.newCall(request)
    val response = call.execute
    convertResponseToCaseClass[AccountSummary](response)
  }

  def cancelSubscription(subscription: Subscription, cancellationDate: LocalDate): String \/ CancelSubscriptionResult = {
    val subscriptionCancellation = SubscriptionCancellation(cancellationDate)
    val body = RequestBody.create(MediaType.parse("application/json"), Json.toJson(subscriptionCancellation).toString)
    val request = buildRequest(config, s"subscriptions/${subscription.id}/cancel").put(body).build()
    val call = restClient.newCall(request)
    logger.info(s"Attempting to Cancel Subscription, using the following command: $subscriptionCancellation")
    val response = call.execute
    convertResponseToCaseClass[CancelSubscriptionResult](response)
  }

  def unpostInvoice(invoice: Invoice): String \/ UpdateInvoiceResult = {
    val invoiceUpdate = InvoiceUpdate(invoice.id, "Draft")
    val body = RequestBody.create(MediaType.parse("application/json"), Json.toJson(invoiceUpdate).toString)
    val request = buildRequest(config, s"object/invoice/${invoice.id}").put(body).build()
    val call = restClient.newCall(request)
    logger.info(s"Attempting to Unpost an invoice with the following command: $invoiceUpdate")
    val response = call.execute
    convertResponseToCaseClass[UpdateInvoiceResult](response)
  }

  def updateCancellationReason(subscription: Subscription): String \/ UpdateSubscriptionResult = {
    val subscriptionUpdate = SubscriptionUpdate("System AutoCancel")
    val body = RequestBody.create(MediaType.parse("application/json"), Json.toJson(subscriptionUpdate).toString)
    val request = buildRequest(config, s"subscriptions/${subscription.id}").put(body).build()
    val call = restClient.newCall(request)
    logger.info(s"Attempting to update Subscription cancellation reason with the following command: $subscriptionUpdate")
    val response = call.execute
    convertResponseToCaseClass[UpdateSubscriptionResult](response)
  }

}

