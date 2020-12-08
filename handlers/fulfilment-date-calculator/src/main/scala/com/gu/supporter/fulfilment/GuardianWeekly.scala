package com.gu.supporter.fulfilment

import java.time.{DayOfWeek, LocalDate}
import java.time.temporal.ChronoUnit.DAYS
import java.time.temporal.TemporalAdjusters.{next, previous}

import com.gu.fulfilmentdates.FulfilmentDates
import com.gu.fulfilmentdates.FulfilmentDates.dayOfWeekFormat

/**
 * @param issueDayOfWeek                Weekday corresponding to publication issue date printed on the paper, for example, Friday for GW
 * @param fulfilmentGenerationDayOfWeek Weekday corresponding to when fulfilment-lambdas generate files
 */
sealed abstract class FulfilmentConstants(
  val issueDayOfWeek: DayOfWeek,
  val fulfilmentGenerationDayOfWeek: DayOfWeek
) {
  def holidayStopFirstAvailableDate(today: LocalDate): LocalDate
}

object GuardianWeeklyFulfilmentDates extends FulfilmentConstants(
  issueDayOfWeek = DayOfWeek.FRIDAY,
  fulfilmentGenerationDayOfWeek = DayOfWeek.THURSDAY,
) {
  def apply(today: LocalDate): Map[String, FulfilmentDates] =
    Map(
      dayOfWeekFormat.format(issueDayOfWeek) -> FulfilmentDates(
        today,
        deliveryAddressChangeEffectiveDate(today),
        holidayStopFirstAvailableDate(today),
        holidayStopProcessorTargetDate(today),
        finalFulfilmentFileGenerationDate(today),
        newSubscriptionEarliestStartDate(today)
      )
    )

  val minDaysBetweenTodayAndFirstAvailableDate = 4

  def holidayStopFirstAvailableDate(today: LocalDate): LocalDate = {
    val dayAfterPublicationDay = issueDayOfWeek.plus(1) // Saturday because GW cover date is a Friday
    if (DAYS.between(today, today `with` next(dayAfterPublicationDay)) < minDaysBetweenTodayAndFirstAvailableDate)
      (today `with` next(dayAfterPublicationDay) `with` next(dayAfterPublicationDay)) // Saturday after next
    else
      (today `with` next(dayAfterPublicationDay)) // next Saturday
  }

  def holidayStopProcessorTargetDate(today: LocalDate): Option[LocalDate] = {
    if (today.getDayOfWeek == fulfilmentGenerationDayOfWeek.minus(1)) {
      Some(today `with` next(issueDayOfWeek) `with` next(issueDayOfWeek)) // issue day after next
    } else {
      None
    }
  }

  // Cover date of first issue sent to the new address.
  def deliveryAddressChangeEffectiveDate(today: LocalDate): LocalDate =
    nextAffectablePublicationDateOnFrontCover(today)

  def newSubscriptionEarliestStartDate(today: LocalDate): LocalDate =
    nextAffectablePublicationDateOnFrontCover(today)

  // TODO: Take into account bank holidays
  def nextAffectablePublicationDateOnFrontCover(today: LocalDate): LocalDate = {

    val todayIsFufilmentDay = today.getDayOfWeek equals fulfilmentGenerationDayOfWeek

    if (todayIsFufilmentDay)
      (today `with` next(issueDayOfWeek) `with` next(issueDayOfWeek) `with` next(issueDayOfWeek))
    else
      (today `with` next(issueDayOfWeek) `with` next(issueDayOfWeek))
  }

  // When was the fulfilment file generated for the nextAffectablePublicationDateOnFrontCover
  def finalFulfilmentFileGenerationDate(today: LocalDate): LocalDate =
    nextAffectablePublicationDateOnFrontCover(today) `with`
      previous(fulfilmentGenerationDayOfWeek) `with`
      previous(fulfilmentGenerationDayOfWeek)

}

