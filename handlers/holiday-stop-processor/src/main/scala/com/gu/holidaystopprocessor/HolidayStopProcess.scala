package com.gu.holidaystopprocessor

import com.gu.salesforce.holiday_stops.SalesforceHolidayStopRequest.HolidayStopRequest
import com.gu.salesforce.holiday_stops.SalesforceHolidayStopRequestActionedZuoraRef.{HolidayStopRequestActionedZuoraAmendmentCode, HolidayStopRequestActionedZuoraAmendmentPrice}

object HolidayStopProcess {

  def apply(config: Config): ProcessResult = {
    val sfCredentials = config.sfCredentials
    val zuoraCredentials = config.zuoraCredentials
    processHolidayStops(
      config,
      getRequests = Salesforce.holidayStopRequests(sfCredentials),
      getSubscription = Zuora.subscriptionGetResponse(zuoraCredentials),
      updateSubscription = Zuora.subscriptionUpdateResponse(zuoraCredentials),
      getLastAmendment = Zuora.lastAmendmentGetResponse(zuoraCredentials),
      exportAmendments = Salesforce.holidayStopUpdateResponse(sfCredentials)
    )
  }

  def processHolidayStops(
    config: Config,
    getRequests: String => Either[OverallFailure, Seq[HolidayStopRequest]],
    getSubscription: String => Either[HolidayStopFailure, Subscription],
    updateSubscription: (Subscription, SubscriptionUpdate) => Either[HolidayStopFailure, Unit],
    getLastAmendment: Subscription => Either[HolidayStopFailure, Amendment],
    exportAmendments: Seq[HolidayStopResponse] => Either[OverallFailure, Unit]
  ): ProcessResult = {
    HolidayStop.holidayStopsToApply(getRequests) match {
      case Left(failure) => ProcessResult(
        holidayStopsToApply = Nil,
        holidayStopResults = Nil,
        overallFailure = Some(failure)
      )
      case Right(holidayStops) =>
        val responses = holidayStops map {
          processHolidayStop(
            config,
            getSubscription,
            updateSubscription,
            getLastAmendment
          )
        }
        val exportResult = exportAmendments(responses.collect {
          case Right(successes) =>
            successes
        })
        ProcessResult(
          holidayStopsToApply = holidayStops,
          holidayStopResults = responses,
          overallFailure = exportResult.left.toOption
        )
    }
  }

  def processHolidayStop(
    config: Config,
    getSubscription: String => Either[HolidayStopFailure, Subscription],
    updateSubscription: (Subscription, SubscriptionUpdate) => Either[HolidayStopFailure, Unit],
    getLastAmendment: Subscription => Either[HolidayStopFailure, Amendment]
  )(stop: HolidayStop): Either[HolidayStopFailure, HolidayStopResponse] =
    for {
      subscription <- getSubscription(stop.subscriptionName)
      _ <- if (subscription.autoRenew) Right(()) else Left(HolidayStopFailure("Cannot currently process non-auto-renewing subscription"))
      update <- Right(SubscriptionUpdate.holidayCreditToAdd(config, subscription, stop.stoppedPublicationDate))
      _ <- updateSubscription(subscription, update)
      amendment <- getLastAmendment(subscription)
    } yield HolidayStopResponse(
      stop.requestId,
      HolidayStopRequestActionedZuoraAmendmentCode(amendment.code),
      HolidayStopRequestActionedZuoraAmendmentPrice(update.price)
    )
}
