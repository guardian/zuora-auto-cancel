package com.gu.holiday_stops

import java.time.LocalDate
import java.time.format.DateTimeFormatter

import com.gu.effects.{FakeFetchString, SFTestEffects, TestingRawEffects}
import com.gu.holiday_stops.ActionCalculator._
import com.gu.holiday_stops.ZuoraSttpEffects.ZuoraSttpEffectsOps
import com.gu.holiday_stops.subscription.{HolidayStopCredit, MutableCalendar, RatePlan, RatePlanCharge, Subscription}
import com.gu.salesforce.{IdentityId, SalesforceContactId}
import com.gu.salesforce.holiday_stops.SalesforceHolidayStopRequestsDetail.SubscriptionName
import com.gu.salesforce.holiday_stops.{SalesForceHolidayStopsEffects, SalesforceHolidayStopRequestsDetail}
import com.gu.util.apigateway.ApiGatewayRequest
import com.gu.util.config.Stage
import com.gu.util.reader.Types.ApiGatewayOp.{ContinueProcessing, ReturnWithResponse}
import com.softwaremill.sttp.testing.SttpBackendStub
import org.scalatest.Inside.inside
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.{JsObject, JsString, JsSuccess, Json}
import com.gu.salesforce.SalesforceHandlerSupport.{HEADER_IDENTITY_ID, HEADER_SALESFORCE_CONTACT_ID}

class HandlerTest extends FlatSpec with Matchers {
  val testId = "test-generated-id"

  it should s"convert either the '$HEADER_IDENTITY_ID' header OR '$HEADER_SALESFORCE_CONTACT_ID' header to Contact or fail" in {

    Handler.extractContactFromHeaders(None) shouldBe a[ReturnWithResponse]
    Handler.extractContactFromHeaders(Some(Map())) shouldBe a[ReturnWithResponse]

    val expectedIdentityIdCoreValue = "identity_id"
    Handler.extractContactFromHeaders(Some(Map(
      HEADER_IDENTITY_ID -> expectedIdentityIdCoreValue
    ))) shouldBe ContinueProcessing(IdentityId(expectedIdentityIdCoreValue))

    val expectedSfContactIdCoreValue = "sf_contact_id"
    Handler.extractContactFromHeaders(Some(Map(
      HEADER_SALESFORCE_CONTACT_ID -> expectedSfContactIdCoreValue
    ))) shouldBe ContinueProcessing(SalesforceContactId(expectedSfContactIdCoreValue))
  }
  "GET /potential/<<sub name>>?startDate=...&endDate=...&estimateCredit=true endpoint" should
    "calculate potential holiday stop dates and estimated credit" in {
    MutableCalendar.setFakeToday(Some(LocalDate.parse("2019-02-01")))
    val subscriptionName = "Sub12344"

    val startDate = LocalDate.of(2019, 1, 1)
    val endDate = startDate.plusMonths(3)
    val customerAcceptanceDate = startDate.plusMonths(1)

    val subscription = Subscription(
      subscriptionNumber = subscriptionName,
      termStartDate = startDate,
      termEndDate = endDate,
      customerAcceptanceDate = customerAcceptanceDate,
      currentTerm = 12,
      currentTermPeriodType = "Month",
      autoRenew = true,
      ratePlans = List(
        RatePlan(
          productName = "Guardian Weekly - Domestic",
          ratePlanName = "GW Oct 18 - Quarterly - Domestic",
          ratePlanCharges =
            List(RatePlanCharge(
              name = "GW Oct 18 - Quarterly - Domestic",
              number = "C1",
              37.50,
              Some("Quarter"),
              effectiveStartDate = startDate,
              chargedThroughDate = Some(endDate),
              HolidayStart__c = None,
              HolidayEnd__c = None,
              processedThroughDate = Some(endDate.minusMonths(3)),
              "",
              specificBillingPeriod = None,
              endDateCondition = Some("Subscription_End"),
              upToPeriodsType = None,
              upToPeriods = None
            )),
          productRatePlanId = "",
          id = ""
        )
      ),
      "Active"
    )

    val testBackend = SttpBackendStub
      .synchronous
      .stubZuoraAuthCall()
      .stubZuoraSubscription(subscriptionName, subscription)

    inside(
      Handler.operationForEffects(
        defaultTestEffects.response,
        Stage("DEV"),
        FakeFetchString.fetchString,
        testBackend,
        "test-generated-id"
      ).map { operation =>
        operation
          .steps(legacyPotentialIssueDateRequest(
            productPrefix = "Guardian Weekly xxx",
            startDate = "2019-01-01",
            endDate = "2019-01-15",
            subscriptionName = subscriptionName,
            estimateCredit = true
          ))
      }
    ) {
      case ContinueProcessing(response) =>
        response.statusCode should equal("200")
        val parsedResponseBody = Json.fromJson[PotentialHolidayStopsResponse](Json.parse(response.body))
        inside(parsedResponseBody) {
          case JsSuccess(response, _) =>
            response should equal(
              PotentialHolidayStopsResponse(
                List(
                  PotentialHolidayStop(LocalDate.of(2019, 1, 4), HolidayStopCredit(-2.89, LocalDate.parse("2019-04-01"))),
                  PotentialHolidayStop(LocalDate.of(2019, 1, 11), HolidayStopCredit(-2.89, LocalDate.parse("2019-04-01"))),
                )
              )
            )
        }
    }
  }
  it should "return bad request if method is missing" in {
    inside(
      Handler
        .operationForEffects(
          defaultTestEffects.response,
          Stage("DEV"),
          FakeFetchString.fetchString,
          SttpBackendStub.synchronous,
          testId
        )
        .map(_.steps(ApiGatewayRequest(None, None, None, None, None, None)))
    ) {
      case ContinueProcessing(response) =>
        response.statusCode should equal("400")
        response.body should equal(
          """{
            |  "message" : "Bad request: Http method is required"
            |}""".stripMargin
        )
    }
  }
  it should "return bad request if path is missing" in {
    inside(
      Handler
        .operationForEffects(
          defaultTestEffects.response,
          Stage("DEV"),
          FakeFetchString.fetchString,
          SttpBackendStub.synchronous,
          testId
        )
        .map(_.steps(ApiGatewayRequest(Some("GET"), None, None, None, None, None)))
    ) {
      case ContinueProcessing(response) =>
        response.statusCode should equal("400")
        response.body should equal(
          """{
            |  "message" : "Bad request: Path is required"
            |}""".stripMargin
        )
    }
  }
  "GET /hsr/<<sub name>> endpoint" should
    "get subscription and calculate product specifics" in {

    val subscriptionName = "Sub12344"
    val gwSubscription = Fixtures.mkGuardianWeeklySubscription()
    val contactId = "Contact1234"
    val holidayStopRequestsDetail = Fixtures.mkHolidayStopRequestDetails()

    val testBackend = SttpBackendStub
      .synchronous
      .stubZuoraAuthCall()
      .stubZuoraSubscription(subscriptionName, gwSubscription)

    val holidayStopRequest = Fixtures.mkHolidayStopRequest(
      id = "holidayStopId",
      subscriptionName = SubscriptionName(subscriptionName),
      requestDetail = List(holidayStopRequestsDetail)
    )

    inside(
      Handler.operationForEffects(
        new TestingRawEffects(
          responses = Map(
            SalesForceHolidayStopsEffects.listHolidayStops(contactId, subscriptionName, List(holidayStopRequest))
          ),
          postResponses = Map(
            SFTestEffects.authSuccess,
          )
        ).response,
        Stage("DEV"),
        FakeFetchString.fetchString,
        testBackend,
        testId
      ).map { operation =>
        operation
          .steps(
            existingHolidayStopsRequest(
              subscriptionName,
              contactId,
              "Newspaper - Voucher Book",
              "Sunday"
            )
          )
      }
    ) {
      case ContinueProcessing(response) =>
        response.statusCode should equal("200")
        val parsedResponseBody = Json.fromJson[GetHolidayStopRequests](Json.parse(response.body))
        inside(parsedResponseBody) {
          case JsSuccess(response, _) =>
            response should equal(
              GetHolidayStopRequests(
                List(
                  HolidayStopRequestFull(
                    holidayStopRequest.Id.value,
                    holidayStopRequest.Start_Date__c.value,
                    holidayStopRequest.End_Date__c.value,
                    holidayStopRequest.Subscription_Name__c,
                    List(toHolidayStopRequestDetail(holidayStopRequestsDetail)),
                    withdrawnTime = None,
                    MutabilityFlags(isFullyMutable = false, isEndDateEditable = false)
                  )
                ),
                List(
                  IssueSpecifics(
                    GuardianWeeklySuspensionConstants.issueConstants.head.firstAvailableDate(LocalDate.now()),
                    GuardianWeeklySuspensionConstants.issueConstants.head.issueDayOfWeek.getValue
                  )
                ),
                GuardianWeeklySuspensionConstants.annualIssueLimit
              )
            )

        }
    }
  }
  "POST /hsr/<<sub name>>/cancel?effectiveCancelationDate=yy-MM-dd endpoint" should
    "update holiday stops detail for cancellation" in {

    val effectiveCancellationDate = LocalDate.of(2019,10,1)
    val subscriptionName = "Sub12344"
    val contactId = "Contact1234"
    val price = 1.23
    val holidayStopRequestsDetail = Fixtures.mkHolidayStopRequestDetails(
      chargeCode = None,
      stopDate = effectiveCancellationDate.minusDays(1),
      estimatedPrice = Some(price)
    )

    val testBackend = SttpBackendStub
      .synchronous

    val holidayStopRequest = Fixtures.mkHolidayStopRequest(
      id = "holidayStopId",
      subscriptionName = SubscriptionName(subscriptionName),
      requestDetail = List(holidayStopRequestsDetail)
    )

    inside(
      Handler.operationForEffects(
        new TestingRawEffects(
          responses = Map(
            SalesForceHolidayStopsEffects.listHolidayStops(contactId, subscriptionName, List(holidayStopRequest))
          ),
          postResponses = Map(
            SFTestEffects.authSuccess,
            SFTestEffects.cancelSuccess(testId, price)
          )
        ).response,
        Stage("DEV"),
        FakeFetchString.fetchString,
        testBackend,
        testId
      ).map { operation =>
        operation
          .steps(
            cancelHolidayStops(
              isPreview = false,
              subscriptionName = subscriptionName,
              sfContactId = contactId,
              effectiveCancellationDate = effectiveCancellationDate
            )
          )
      }
    ) {
      case ContinueProcessing(response) =>
        response.statusCode should equal("200")
    }
  }

  "GET /hsr/<<sub name>>/cancel?effectiveCancelationDate=yy-MM-dd endpoint" should
    "get holiday stop details that should be refunded if the subscription is canceled" in {

    val effectiveCancellationDate = LocalDate.of(2019,10,1)
    val subscriptionName = "Sub12344"
    val contactId = "Contact1234"
    val price = 1.23
    val stopDate = effectiveCancellationDate.minusDays(1)
    val invoiceDate = effectiveCancellationDate.plusWeeks(1)
    val holidayStopRequestsDetail = Fixtures.mkHolidayStopRequestDetails(
      chargeCode = None,
      stopDate = stopDate,
      estimatedPrice = Some(price),
      expectedInvoiceDate = Some(invoiceDate)

    )

    val testBackend = SttpBackendStub
      .synchronous

    val holidayStopRequest = Fixtures.mkHolidayStopRequest(
      id = "holidayStopId",
      subscriptionName = SubscriptionName(subscriptionName),
      requestDetail = List(holidayStopRequestsDetail)
    )

    inside(
      Handler.operationForEffects(
        new TestingRawEffects(
          responses = Map(
            SalesForceHolidayStopsEffects.listHolidayStops(contactId, subscriptionName, List(holidayStopRequest))
          ),
          postResponses = Map(
            SFTestEffects.authSuccess,
          )
        ).response,
        Stage("DEV"),
        FakeFetchString.fetchString,
        testBackend,
        testId
      ).map { operation =>
        operation
          .steps(
            cancelHolidayStops(
              isPreview = true,
              subscriptionName = subscriptionName,
              sfContactId = contactId,
              effectiveCancellationDate = effectiveCancellationDate
            )
          )
      }
    ) {
      case ContinueProcessing(response) =>
        response.statusCode should equal("200")
        val parsedResponseBody = Json.fromJson[GetCancellationDetails](Json.parse(response.body))
        inside(parsedResponseBody) {
          case JsSuccess(response, _) =>
            response.publicationsToRefund should contain only(
              HolidayStopRequestsDetail(stopDate, Some(price), Some(price), Some(invoiceDate))
            )
        }
    }
  }

  private def toHolidayStopRequestDetail(holidayStop: SalesforceHolidayStopRequestsDetail.HolidayStopRequestsDetail) = {
    HolidayStopRequestsDetail(
      holidayStop.Stopped_Publication_Date__c.value,
      holidayStop.Estimated_Price__c.map(_.value),
      holidayStop.Actual_Price__c.map(_.value),
      holidayStop.Expected_Invoice_Date__c.map(_.value)
    )
  }

  private def legacyPotentialIssueDateRequest(productPrefix: String, startDate: String, endDate: String,
                                        subscriptionName: String, estimateCredit: Boolean) = {
    ApiGatewayRequest(
      Some("GET"),
      Some(Map(
        "startDate" -> startDate,
        "endDate" -> endDate,
        "estimateCredit" -> (if (estimateCredit) "true" else "false"))),
      None,
      Some(Map("x-product-name-prefix" -> productPrefix)),
      Some(JsObject(Seq("subscriptionName" -> JsString(subscriptionName)))),
      Some(s"/potential/$subscriptionName ")
    )
  }

  private def potentialIssueDateRequest(productType: String, productRatePlanName: String, startDate: String,
                                        endDate: String, subscriptionName: String, estimateCredit: Boolean) = {
    ApiGatewayRequest(
      Some("GET"),
      Some(Map(
        "startDate" -> startDate,
        "endDate" -> endDate,
        "estimateCredit" -> (if (estimateCredit) "true" else "false"),
        "productType" -> productType,
        "productRatePlanName" -> productRatePlanName
      )),
      None,
      None,
      Some(JsObject(Seq("subscriptionName" -> JsString(subscriptionName)))),
      Some(s"/potential/$subscriptionName ")
    )
  }

  private def existingHolidayStopsRequest(subscriptionName: String, sfContactId: String, productType: String, produtRatePlanName: String) = {
    ApiGatewayRequest(
      Some("GET"),
      Some(Map(
        "productType" -> productType,
        "productRatePlanName" -> produtRatePlanName
      )),
      None,
      Some(Map("x-salesforce-contact-id" -> sfContactId)),
      Some(JsObject(Seq("subscriptionName" -> JsString(subscriptionName)))),
      Some(s"/hsr/$subscriptionName ")
    )
  }

  private def cancelHolidayStops(isPreview: Boolean, subscriptionName: String, sfContactId: String, effectiveCancellationDate: LocalDate) = {
    val method = if(isPreview) "GET" else "POST"
    ApiGatewayRequest(
      Some(method),
      Some(Map(
        "effectiveCancellationDate" -> effectiveCancellationDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
      )),
      None,
      Some(Map("x-salesforce-contact-id" -> sfContactId)),
      Some(JsObject(Seq("subscriptionName" -> JsString(subscriptionName)))),
      Some(s"/hsr/$subscriptionName/cancel")
    )
  }

  val defaultTestEffects = new TestingRawEffects(
    postResponses = Map(
      SFTestEffects.authSuccess
    )
  )
}
