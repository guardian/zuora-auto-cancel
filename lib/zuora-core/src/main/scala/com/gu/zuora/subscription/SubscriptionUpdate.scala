package com.gu.zuora.subscription

import java.time.LocalDate

case class SubscriptionUpdate(
  currentTerm: Option[Int],
  currentTermPeriodType: Option[String],
  add: List[Add]
)

/**
 * This builds the request body to add a Credit RatePlanCharge in Zuora.
 * It should not contain business logic. Any business logic should be moved out to the
 * main for-comprehension.
 */
object SubscriptionUpdate {

  def forHolidayStop(
    creditProduct: CreditProduct,
    subscription: Subscription,
    stoppedPublicationDate: AffectedPublicationDate
  ): ZuoraApiResponse[SubscriptionUpdate] =
    for {
      subscriptionData <- SubscriptionData(subscription)
      issueData <- subscriptionData.issueDataForDate(stoppedPublicationDate.value)
    } yield {
      val maybeExtendedTerm = ExtendedTerm(issueData.nextBillingPeriodStartDate, subscription)
      val holidayCredit = HolidayStopCredit(issueData.credit, issueData.nextBillingPeriodStartDate)
      SubscriptionUpdate(
        currentTerm = maybeExtendedTerm.map(_.length),
        currentTermPeriodType = maybeExtendedTerm.map(_.unit),
        List(
          Add(
            productRatePlanId = creditProduct.productRatePlanId,
            contractEffectiveDate = holidayCredit.invoiceDate,
            customerAcceptanceDate = holidayCredit.invoiceDate,
            serviceActivationDate = holidayCredit.invoiceDate,
            chargeOverrides = List(
              ChargeOverride(
                productRatePlanChargeId = creditProduct.productRatePlanChargeId,
                HolidayStart__c = stoppedPublicationDate.value,
                HolidayEnd__c = stoppedPublicationDate.value,
                price = holidayCredit.amount
              )
            )
          )
        )
      )
    }
}

case class Add(
  productRatePlanId: String,
  contractEffectiveDate: LocalDate,
  customerAcceptanceDate: LocalDate,
  serviceActivationDate: LocalDate,
  chargeOverrides: List[ChargeOverride]
)

case class ChargeOverride(
  productRatePlanChargeId: String,
  HolidayStart__c: LocalDate,
  HolidayEnd__c: LocalDate,
  price: Double
)
