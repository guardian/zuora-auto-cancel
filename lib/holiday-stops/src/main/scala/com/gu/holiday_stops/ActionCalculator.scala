package com.gu.holiday_stops

import java.time.temporal.ChronoUnit.DAYS
import java.time.temporal.{ChronoUnit, TemporalAdjusters}
import java.time.{DayOfWeek, LocalDate}

import cats.data.NonEmptyList
import cats.kernel.Order
import com.gu.holiday_stops.subscription.Subscription
import com.gu.holiday_stops.ProductVariant._

case class ProductSpecifics(
  annualIssueLimit: Int,
  issueSpecifics: List[IssueSpecifics]
)

case class IssueSpecifics(
  firstAvailableDate: LocalDate,
  issueDayOfWeek: Int,
)

/**
 * For example, UAT A-S00079571
 *    - Salesforce UAT https://gnmtouchpoint--uat.cs82.my.salesforce.com/a2k3E000000pFGgQAM
 *    - https://apisandbox.zuora.com/apps/NewInvoice.do?method=view&invoice_number=INV00102837
 *
 * Holiday:                             Thu 13/08/2020 - Thu 27/08/2020
 * Publication 1 issue date:            Fri 14/08/2020
 * Publication 1 fulfillment date:      Thu 06/08/2020
 * Publication 1 processor run date:    Thu 05/08/2020
 * processDateOverride for test:        Fri 2020-08-14
 *
 * Holiday:                             Thu 13/08/2020 - Thu 27/08/2020
 * Publication 1 issue date:            Fri 21/08/2020
 * Publication 1 fulfillment date:      Thu 13/08/2020
 * Publication 1 processor run date:    Thu 12/08/2020
 * processDateOverride for test:        Fri 2020-08-21
 */
object ActionCalculator {


  /**
   * @param annualIssueLimit         Maximum number of days holidays that can be taken annually for a subscription
   * @param issueConstants           Constants specific to the issues covered by the type of subscription
   */
  case class SuspensionConstants(
    annualIssueLimit: Int,
    issueConstants: List[IssueSuspensionConstants]
  )

  /**
   * @param issueDayOfWeek           Weekday corresponding to publication issue date printed on the paper, for example, Friday for GW
   * @param processorRunLeadTimeDays Number of days (including one safety-net day) before publication issue date when the holiday processor runs.
   *                                 One safety-day before fulfilment day. Safety day gives us an opportunity to fix issues before fulfilment runs.
   */
  sealed abstract class IssueSuspensionConstants(
    val issueDayOfWeek: DayOfWeek,
    val processorRunLeadTimeDays: Int
  ) {
    /**
     * The first date a holiday can started on
     */
    def firstAvailableDate(today: LocalDate): LocalDate
  }

  val GuardianWeeklySuspensionConstants = SuspensionConstants(
    annualIssueLimit = 6,
    issueConstants = List(GuardianWeeklyIssueSuspensionConstants)
  )

  case object GuardianWeeklyIssueSuspensionConstants extends IssueSuspensionConstants(
    issueDayOfWeek = DayOfWeek.FRIDAY,
    processorRunLeadTimeDays = 8 + (1 /* safety-day */ ), //one (safety) day before the Thursday of the week before the Friday issue day
  ) {
    val minDaysBetweenTodayAndFirstAvailableDate = 5
    val maxDaysBetweenTodayAndFirstAvailableDate = 11
    val firstAvailableDateDayOfWeek = DayOfWeek.SATURDAY

    /**
     * If there are less than 5 days between today and the day after next publication day,
     * then Saturday after next (i.e., next-next Saturday),
     * otherwise next Saturday
     */
    def firstAvailableDate(today: LocalDate): LocalDate = {
      val dayAfterNextPublicationDay = TemporalAdjusters.next(issueDayOfWeek.plus(1)) // Saturday because GW is published on Friday, https://stackoverflow.com/a/29010338/5205022
      val firstAvailableDate: LocalDate =
        if (DAYS.between(today, today `with` dayAfterNextPublicationDay) < minDaysBetweenTodayAndFirstAvailableDate)
          (today `with` dayAfterNextPublicationDay `with` dayAfterNextPublicationDay) // Saturday after next
        else
          (today `with` dayAfterNextPublicationDay) // next Saturday

      verify(firstAvailableDate, today)
      firstAvailableDate
    }

    private def verify(firstAvailableDate: LocalDate, today: LocalDate): Unit = {
      val daysBetweenTodayAndFirstAvailableDate = ChronoUnit.DAYS.between(today, firstAvailableDate)
      require(
        (daysBetweenTodayAndFirstAvailableDate >= minDaysBetweenTodayAndFirstAvailableDate) &&
          (daysBetweenTodayAndFirstAvailableDate <= maxDaysBetweenTodayAndFirstAvailableDate),
        "Guardian Weekly first available date should be between 5 and 11 days from today"
      )
      require(firstAvailableDate.getDayOfWeek == firstAvailableDateDayOfWeek, "Guardian Weekly first available date should fall on Saturday")
    }
  }

  val SaturdayVoucherSuspensionConstants = voucherSuspensionConstants(
    List(voucherIssueSuspensionConstants(DayOfWeek.SATURDAY))
  )

  val SundayVoucherSuspensionConstants = voucherSuspensionConstants(
    List(voucherIssueSuspensionConstants(DayOfWeek.SUNDAY))
  )

  val WeekendVoucherSuspensionConstants = voucherSuspensionConstants(
    List(
      voucherIssueSuspensionConstants(DayOfWeek.SATURDAY),
      voucherIssueSuspensionConstants(DayOfWeek.SUNDAY)
    )
  )

  val SixdayVoucherSuspensionConstants = voucherSuspensionConstants(
    List(
      voucherIssueSuspensionConstants(DayOfWeek.MONDAY),
      voucherIssueSuspensionConstants(DayOfWeek.TUESDAY),
      voucherIssueSuspensionConstants(DayOfWeek.WEDNESDAY),
      voucherIssueSuspensionConstants(DayOfWeek.THURSDAY),
      voucherIssueSuspensionConstants(DayOfWeek.FRIDAY),
      voucherIssueSuspensionConstants(DayOfWeek.SATURDAY),
    )
  )

  val EverydayVoucherSuspensionConstants = voucherSuspensionConstants(
    List(
      voucherIssueSuspensionConstants(DayOfWeek.MONDAY),
      voucherIssueSuspensionConstants(DayOfWeek.TUESDAY),
      voucherIssueSuspensionConstants(DayOfWeek.WEDNESDAY),
      voucherIssueSuspensionConstants(DayOfWeek.THURSDAY),
      voucherIssueSuspensionConstants(DayOfWeek.FRIDAY),
      voucherIssueSuspensionConstants(DayOfWeek.SATURDAY),
      voucherIssueSuspensionConstants(DayOfWeek.SUNDAY),
    )
  )

  val EverydayPlusVoucherSuspensionConstants = EverydayVoucherSuspensionConstants
  val SixdayPlusVoucherSuspensionConstants = SixdayVoucherSuspensionConstants
  val WeekendPlusVoucherSuspensionConstants = WeekendVoucherSuspensionConstants
  val SundayPlusVoucherSuspensionConstants = SundayVoucherSuspensionConstants
  val SaturdayPlusVoucherSuspensionConstants = SaturdayVoucherSuspensionConstants

  def voucherSuspensionConstants(issueSuspensionConstants: List[IssueSuspensionConstants]) =
    SuspensionConstants(issueSuspensionConstants.size * 6, issueSuspensionConstants)

  lazy val VoucherProcessorLeadTime: Int = 1

  def voucherIssueSuspensionConstants(dayOfWeek: DayOfWeek): IssueSuspensionConstants =
    new IssueSuspensionConstants(
      issueDayOfWeek = dayOfWeek,
      processorRunLeadTimeDays = VoucherProcessorLeadTime
    ) {
      def firstAvailableDate(today: LocalDate): LocalDate = today.plusDays(processorRunLeadTimeDays.toLong)
    }

  def suspensionConstantsByProductVariant(productVariant: ProductVariant): Either[ActionCalculatorError, SuspensionConstants] =
    productVariant match {
      case GuardianWeekly => Right(GuardianWeeklySuspensionConstants)
      case SaturdayVoucher => Right(SaturdayVoucherSuspensionConstants)
      case SundayVoucher => Right(SundayVoucherSuspensionConstants)
      case WeekendVoucher => Right(WeekendVoucherSuspensionConstants)
      case SixdayVoucher => Right(SixdayVoucherSuspensionConstants)
      case EverydayVoucher => Right(EverydayVoucherSuspensionConstants)
      case SaturdayPlusVoucher => Right(SaturdayPlusVoucherSuspensionConstants)
      case SundayPlusVoucher => Right(SundayPlusVoucherSuspensionConstants)
      case WeekendPlusVoucher => Right(WeekendPlusVoucherSuspensionConstants)
      case SixdayPlusVoucher => Right(SixdayPlusVoucherSuspensionConstants)
      case EverydayPlusVoucher => Right(EverydayPlusVoucherSuspensionConstants)
      case _ => Left(ActionCalculatorError(s"ProductRatePlan $productVariant is not supported"))
    }

  def getProductSpecificsByProductVariant(
    productVariant: ProductVariant,
    subscription: Subscription,
    today: LocalDate = LocalDate.now()
  ): Either[ActionCalculatorError, ProductSpecifics] = {
    suspensionConstantsByProductVariant(productVariant)
      .map { constants =>
        ProductSpecifics(
          constants.annualIssueLimit,
          constants.issueConstants.map { issueConstants =>
            IssueSpecifics(
              firstAvailableDate = latestOf(
                subscription.fulfilmentStartDate,
                issueConstants.firstAvailableDate(today)
              ),
              issueDayOfWeek = issueConstants.issueDayOfWeek.getValue
            )
          }
        )
      }
  }

  def publicationDatesToBeStopped(
    fromInclusive: LocalDate,
    toInclusive: LocalDate,
    productVariant: ProductVariant
  ): Either[ActionCalculatorError, List[LocalDate]] = {

    suspensionConstantsByProductVariant(productVariant).map { suspensionConstants =>
      val daysOfPublication = suspensionConstants.issueConstants.map(_.issueDayOfWeek)

      def isPublicationDay(currentDayWithinHoliday: Long) =
        daysOfPublication.contains(fromInclusive.plusDays(currentDayWithinHoliday).getDayOfWeek)

      def stoppedDate(currentDayWithinHoliday: Long) = fromInclusive.plusDays(currentDayWithinHoliday)

      val holidayLengthInDays = 0 to ChronoUnit.DAYS.between(fromInclusive, toInclusive).toInt
      holidayLengthInDays.toList.collect { case day if isPublicationDay(day.toLong) => stoppedDate(day.toLong) }
    }
  }

  def latestOf(head: LocalDate, tail: LocalDate*) = {
    NonEmptyList(head, tail.toList)
      .sorted(Order.fromLessThan[LocalDate](_.isAfter(_))).head
  }
}

case class ActionCalculatorError(message: String)