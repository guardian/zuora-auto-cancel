package com.gu.holidaystopbackfill

import java.io.File

import com.gu.holidaystopbackfill.SalesforceHolidayStop._
import com.gu.holidaystopbackfill.ZuoraHolidayStop.holidayStopsAlreadyInZuora

object Backfiller {

  /*
   * This makes two passes to update Salesforce to ensure it's failsafe.
   * If any call fails it should leave Salesforce in a consistent state.
   * First, the holiday request table is updated, and then the zuora refs child table.
   */
  def backfill(src: File, dryRun: Boolean, stage: String): Either[BackfillFailure, BackfillResult] = {
    for {
      config <- Config(stage)
      stopsInZuora <- Right(holidayStopsAlreadyInZuora(src))
      minStartDate = stopsInZuora.map(_.startDate).minBy(_.toEpochDay)
      maxEndDate = stopsInZuora.map(_.endDate).maxBy(_.toEpochDay)
      requestsInSf1 <- holidayStopRequestsAlreadyInSalesforce(config)(minStartDate, maxEndDate)
      requestsToAddToSf = holidayStopRequestsToBeBackfilled(stopsInZuora, requestsInSf1)
      _ <- holidayStopRequestsAddedToSalesforce(config, dryRun)(requestsToAddToSf)
      requestsInSf2 <- holidayStopRequestsAlreadyInSalesforce(config)(minStartDate, maxEndDate)
      detailsToAddToSf = detailsToBeBackfilled(stopsInZuora, requestsInSf2)
      _ <- detailsAddedToSalesforce(config, dryRun)(detailsToAddToSf)
    } yield BackfillResult(requestsToAddToSf, detailsToAddToSf)
  }
}
