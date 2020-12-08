package com.gu.digital_voucher_api

import java.time.LocalDate

import cats.Monad
import cats.syntax.all._
import cats.data.EitherT
import com.gu.imovo.{ImovoClient, ImovoSubscriptionResponse, ImovoSubscriptionType, SchemeName, SfSubscriptionId}

trait DigitalVoucherService[F[_]] {
  def createVoucher(subscriptionId: SfSubscriptionId, ratePlanName: RatePlanName): EitherT[F, DigitalVoucherServiceError, SubscriptionVouchers]
  def replaceVoucher(subscriptionId: SfSubscriptionId, typeOfReplacement: ImovoSubscriptionType): EitherT[F, DigitalVoucherServiceError, ReplacementSubscriptionVouchers]
  def getVoucher(subscriptionId: String): EitherT[F, DigitalVoucherServiceError, SubscriptionVouchers]
  def cancelVouchers(subscriptionId: SfSubscriptionId, cancellationDate: LocalDate): EitherT[F, DigitalVoucherServiceError, Unit]
  def suspendVoucher(subscriptionId: SfSubscriptionId, startDate: LocalDate, endDateExclusive: LocalDate): EitherT[F, DigitalVoucherServiceError, Unit]
  def getRedemptionHistory(subscriptionId: String): EitherT[F, DigitalVoucherServiceError, RedemptionHistory]
}

object DigitalVoucherService {

  private val schemeNames: Map[RatePlanName, SchemeName] = {
    val everydaySchemeName = SchemeName("Guardian7Day")
    val sundaySchemeName = SchemeName("GuardianSunday")
    val weekendSchemeName = SchemeName("GuardianWeekend")
    val sixDaySchemeName = SchemeName("Guardian6Day")

    Map(
      RatePlanName("Everyday") -> everydaySchemeName,
      RatePlanName("Everyday+") -> everydaySchemeName,
      RatePlanName("Sunday") -> sundaySchemeName,
      RatePlanName("Sunday+") -> sundaySchemeName,
      RatePlanName("Weekend") -> weekendSchemeName,
      RatePlanName("Weekend+") -> weekendSchemeName,
      RatePlanName("Sixday") -> sixDaySchemeName,
      RatePlanName("Sixday+") -> sixDaySchemeName,
    )
  }

  def apply[F[_]: Monad](imovoClient: ImovoClient[F]): DigitalVoucherService[F] = new DigitalVoucherService[F] {

    override def createVoucher(
      subscriptionId: SfSubscriptionId,
      ratePlanName: RatePlanName
    ): EitherT[F, DigitalVoucherServiceError, SubscriptionVouchers] = {
      val tomorrow = LocalDate.now.plusDays(1)

      for {
        schemeName <- schemeNames
          .get(ratePlanName)
          .toRight(InvalidArgumentException(s"Rate plan name has no matching scheme name: $ratePlanName"))
          .toEitherT[F]
        voucherResponse <- imovoClient
          .createSubscriptionVoucher(subscriptionId, schemeName, tomorrow)
          .leftMap { error =>
            ImovoOperationFailedException(error.message)
          }
          .recoverWith {
            case createError =>
              imovoClient
                .getSubscriptionVoucher(subscriptionId.value)
                .leftMap { error =>
                  ImovoOperationFailedException(
                    s"Imovo create request failed:${createError.message} " +
                      s"and the Imovo get request failed: ${error.message}"
                  )
                }
          }
        voucher <- toVoucher(voucherResponse).toEitherT[F]
      } yield voucher
    }

    def toVoucher(voucherResponse: ImovoSubscriptionResponse): Either[DigitalVoucherServiceError, SubscriptionVouchers] = {
      (
        voucherResponse
        .subscriptionVouchers
        .find(_.subscriptionType === ImovoSubscriptionType.ActiveLetter.value)
        .toRight(List("Imovo response did not contain an subscription voucher where subscriptionType==\"ActiveLetter\" ")),
        voucherResponse
        .subscriptionVouchers
        .find(_.subscriptionType === ImovoSubscriptionType.ActiveCard.value)
        .toRight(List("Imovo response did not contain an subscription voucher where subscriptionType==\"ActiveCard\" "))
      ).parMapN { (letterVoucher, cardVoucher) =>
          SubscriptionVouchers(cardVoucher.voucherCode, letterVoucher.voucherCode)
        }.leftMap { errors =>
          DigitalVoucherServiceFailure(errors.mkString(","))
        }
    }

    def toReplacementVoucher(voucherResponse: ImovoSubscriptionResponse): Either[DigitalVoucherServiceError, ReplacementSubscriptionVouchers] = {
      val cardCode = voucherResponse.subscriptionVouchers.find(_.subscriptionType === ImovoSubscriptionType.ActiveCard.value)
      val letterCode = voucherResponse.subscriptionVouchers.find(_.subscriptionType === ImovoSubscriptionType.ActiveLetter.value)

      (cardCode, letterCode) match {
        case (Some(card), Some(letter)) => Right(ReplacementSubscriptionVouchers(Some(card.voucherCode), Some(letter.voucherCode)))
        case (Some(card), None) => Right(ReplacementSubscriptionVouchers(Some(card.voucherCode), None))
        case (None, Some(letter)) => Right(ReplacementSubscriptionVouchers(None, Some(letter.voucherCode)))
        case _ => {
          Left(DigitalVoucherServiceFailure(List(
            "Imovo response did not contain an subscription voucher where subscriptionType==\"ActiveLetter\" ",
            "Imovo response did not contain an subscription voucher where subscriptionType==\"ActiveCard\" "
          ).mkString(",")))
        }
      }
    }

    override def cancelVouchers(subscriptionId: SfSubscriptionId, cancellationDate: LocalDate): EitherT[F, DigitalVoucherServiceError, Unit] = {
      val lastActiveDate = cancellationDate.minusDays(1)
      imovoClient
        .cancelSubscriptionVoucher(subscriptionId, Some(lastActiveDate))
        .map(_ => ())
        .leftMap(error => DigitalVoucherServiceFailure(error.message))
    }

    override def getVoucher(subscriptionId: String): EitherT[F, DigitalVoucherServiceError, SubscriptionVouchers] =
      for {
        voucherResponse <- imovoClient
          .getSubscriptionVoucher(subscriptionId)
          .leftMap(error => DigitalVoucherServiceFailure(error.message))
        voucher <- toVoucher(voucherResponse).toEitherT[F]
      } yield voucher

    override def replaceVoucher(subscriptionId: SfSubscriptionId, typeOfReplacement: ImovoSubscriptionType): EitherT[F, DigitalVoucherServiceError, ReplacementSubscriptionVouchers] = {
      for {
        voucherResponse <- imovoClient
          .replaceSubscriptionVoucher(subscriptionId, typeOfReplacement)
          .leftMap(error => DigitalVoucherServiceFailure(error.message))
        voucher <- toReplacementVoucher(voucherResponse).toEitherT[F]
      } yield voucher
    }

    def suspendVoucher(subscriptionId: SfSubscriptionId, startDate: LocalDate, endDateExclusive: LocalDate): EitherT[F, DigitalVoucherServiceError, Unit] =
      imovoClient
        .suspendSubscriptionVoucher(subscriptionId, startDate, endDateExclusive)
        .bimap(error => ImovoOperationFailedException(error.message), _ => ())

    override def getRedemptionHistory(subscriptionId: String): EitherT[F, DigitalVoucherServiceError, RedemptionHistory] = {
      imovoClient
        .getRedemptionHistory(SfSubscriptionId(subscriptionId))
        .map(response => RedemptionHistory(response.voucherHistoryItem.map(item => RedemptionAttempt(item))))
        .leftMap(error => DigitalVoucherServiceFailure(error.message))
    }

  }
}
