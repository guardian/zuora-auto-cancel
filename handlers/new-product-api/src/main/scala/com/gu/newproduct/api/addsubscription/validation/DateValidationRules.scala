package com.gu.newproduct.api.addsubscription.validation

import java.time.temporal.TemporalAdjusters
import java.time.{DayOfWeek, LocalDate}

import com.gu.newproduct.api.addsubscription.validation.Validation._

case class Days(value: Int) extends AnyVal

trait DateRule {
  def isValid(d: LocalDate): ValidationResult[Unit]
}

object DateRule {

  implicit class OptionalRuleConverter(maybeRule: Option[DateRule]) {
    def alwaysValid(d: LocalDate) = Passed(())

    def isValid: LocalDate => ValidationResult[Unit] = maybeRule.map(rule => rule.isValid _).getOrElse(alwaysValid _)
  }

}

case class StartDateRules(
  daysOfWeekRule: Option[DaysOfWeekRule] = None,
  windowRule: Option[WindowRule] = None
) extends DateRule {
  override def isValid(d: LocalDate): ValidationResult[Unit] = for {
    _ <- daysOfWeekRule.isValid(d)
    _ <- windowRule.isValid(d)
  } yield (Passed(()))
}

case class DaysOfWeekRule(allowedDays: List[DayOfWeek]) extends DateRule {

  override def isValid(d: LocalDate): ValidationResult[Unit] = allowedDays contains d.getDayOfWeek orFailWith errorMessage(d)

  private def errorMessage(date: LocalDate) = {
    val allowedDaysStr = allowedDays.mkString(",")
    val dateDayStr = date.getDayOfWeek.toString
    s"invalid day of the week: $date is a $dateDayStr. Allowed days are $allowedDaysStr"
  }
}

case class WindowRule
(
  now: () => LocalDate,
  cutOffDay: Option[DayOfWeek],
  startDelay: Option[Days],
  size: Option[Days],
) extends DateRule {
  override def isValid(d: LocalDate): ValidationResult[Unit] = {
    val today = now()
    val maybeCutOffDate = cutOffDay.map(cutoffDayOfweek => today.`with`(TemporalAdjusters.next(cutoffDayOfweek)))
    val baseDate = maybeCutOffDate.getOrElse(today)
    val startOffset = startDelay.map(_.value).getOrElse(0)
    val startDate = baseDate.plusDays(startOffset)
    val isBeforeWindowStartDay = d.isBefore(startDate)
    val maybeWindowEnd = size.map { windowSize => baseDate.plusDays(windowSize.value) }

    def isOnWindowEndDayOrAfter = maybeWindowEnd.map(!d.isBefore(_)).getOrElse(false)

    def errorMessage = s"$d is out of the selectable range: [$startDate - ${maybeWindowEnd.map(_.toString).getOrElse("")})"

    !isBeforeWindowStartDay && !isOnWindowEndDayOrAfter orFailWith (errorMessage)
  }

}


