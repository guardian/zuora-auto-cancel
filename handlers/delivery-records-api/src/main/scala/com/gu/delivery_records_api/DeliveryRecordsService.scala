package com.gu.delivery_records_api

import java.time.LocalDate

import cats.Monad
import cats.data.EitherT
import com.gu.salesforce.{Contact, RecordsWrapperCaseClass}
import com.gu.salesforce.sttp.SalesforceClient
import io.circe.generic.auto._
import cats.implicits._
import com.gu.salesforce.SalesforceQueryConstants.{contactToWhereClausePart, escapeString}

import scala.annotation.tailrec

final case class DeliveryRecord(
  deliveryDate: Option[LocalDate],
  deliveryInstruction: Option[String],
  deliveryAddress: Option[String],
  addressLine1: Option[String],
  addressLine2: Option[String],
  addressLine3: Option[String],
  addressTown: Option[String],
  addressCountry: Option[String],
  addressPostcode: Option[String],
  hasHolidayStop: Option[Boolean],
  problemCaseId: Option[String],
  isChangedAddress: Option[Boolean],
  isChangedDeliveryInstruction: Option[Boolean]
)

final case class DeliveryProblemCase(
  id: String,
  subject: Option[String],
  description: Option[String],
  problemType: Option[String]
)

sealed trait DeliveryRecordServiceError

case class DeliveryRecordServiceGenericError(message: String) extends DeliveryRecordServiceError

case class DeliveryRecordServiceSubscriptionNotFound(message: String) extends DeliveryRecordServiceError

trait DeliveryRecordsService[F[_]] {
  def getDeliveryRecordsForSubscription(
    subscriptionId: String,
    contact: Contact,
    optionalStartDate: Option[LocalDate],
    optionalEndDate: Option[LocalDate]
  ): EitherT[F, DeliveryRecordServiceError, DeliveryRecordsApiResponse]
}

object DeliveryRecordsService {

  private case class SubscriptionRecordQueryResult(
    Delivery_Records__r: Option[RecordsWrapperCaseClass[SFApiDeliveryRecord]]
  )

  @tailrec
  private def detectChangeSkippingNoneAtHead[T](
    allPrevious: List[DeliveryRecord],
    fieldExtractor: DeliveryRecord => Option[T]
  )(
    value: T
  ): Boolean = allPrevious match {
    case head :: tail => fieldExtractor(head) match {
      case Some(previousValue) => value != previousValue // this detects the change
      case None => detectChangeSkippingNoneAtHead(tail, fieldExtractor)(value) // this skips over a None at the head
    }
    case Nil => false // if we reach the beginning of the list and we don't have an address to compare to, don't mark this as changed
  }

  private def transformSfApiDeliveryRecords(accumulator: List[DeliveryRecord], sfRecord: SFApiDeliveryRecord): List[DeliveryRecord] =
    DeliveryRecord(
      deliveryDate = sfRecord.Delivery_Date__c,
      deliveryAddress = sfRecord.Delivery_Address__c,
      addressLine1 = sfRecord.Address_Line_1__c,
      addressLine2 = sfRecord.Address_Line_2__c,
      addressLine3 = sfRecord.Address_Line_3__c,
      addressTown = sfRecord.Address_Town__c,
      addressCountry = sfRecord.Address_Country__c,
      addressPostcode = sfRecord.Address_Postcode__c,
      deliveryInstruction = sfRecord.Delivery_Instructions__c,
      hasHolidayStop = sfRecord.Has_Holiday_Stop__c,
      problemCaseId = sfRecord.Case__r.map(_.Id),
      isChangedAddress = sfRecord.Delivery_Address__c.map(detectChangeSkippingNoneAtHead(accumulator, _.deliveryAddress)),
      isChangedDeliveryInstruction = sfRecord.Delivery_Instructions__c.map(detectChangeSkippingNoneAtHead(accumulator, _.deliveryInstruction))
    ) :: accumulator

  def apply[F[_]: Monad](salesforceClient: SalesforceClient[F]): DeliveryRecordsService[F] = new DeliveryRecordsService[F] {
    override def getDeliveryRecordsForSubscription(
      subscriptionId: String,
      contact: Contact,
      optionalStartDate: Option[LocalDate],
      optionalEndDate: Option[LocalDate]
    ): EitherT[F, DeliveryRecordServiceError, DeliveryRecordsApiResponse] =
      for {
        queryResult <- queryForDeliveryRecords(
          salesforceClient,
          subscriptionId,
          contact,
          optionalStartDate,
          optionalEndDate
        )
        records <- getDeliveryRecordsFromQueryResults(subscriptionId, contact, queryResult).toEitherT[F]
        results = records.reverse.foldLeft(List.empty[DeliveryRecord])(transformSfApiDeliveryRecords)
        deliveryProblemMap = records.flatMap(
          _.Case__r.map(
            problemCase => problemCase.Id -> DeliveryProblemCase(
              id = problemCase.Id,
              subject = problemCase.Subject,
              description = problemCase.Description,
              problemType = problemCase.Case_Closure_Reason__c
            )
          )
        ).toMap
      } yield DeliveryRecordsApiResponse(results, deliveryProblemMap)

    private def queryForDeliveryRecords(
      salesforceClient: SalesforceClient[F],
      subscriptionId: String,
      contact: Contact,
      optionalStartDate: Option[LocalDate],
      optionalEndDate: Option[LocalDate]
    ): EitherT[F, DeliveryRecordServiceError, RecordsWrapperCaseClass[SubscriptionRecordQueryResult]] = {
      salesforceClient.query[SubscriptionRecordQueryResult](
        deliveryRecordsQuery(contact, subscriptionId, optionalStartDate, optionalEndDate)
      )
        .leftMap(error => DeliveryRecordServiceGenericError(error.toString))
    }

    private def getDeliveryRecordsFromQueryResults(
      subscriptionId: String,
      contact: Contact,
      queryResult: RecordsWrapperCaseClass[SubscriptionRecordQueryResult]
    ): Either[DeliveryRecordServiceError, List[SFApiDeliveryRecord]] = {
      queryResult
        .records
        .headOption
        .toRight(
          DeliveryRecordServiceSubscriptionNotFound(
            s"Subscription '$subscriptionId' not found or did not belong to contact " +
              s"'${contact}'"
          )
        )
        .map(deliverRecordsOption => deliverRecordsOption.Delivery_Records__r.map(_.records).getOrElse(Nil))
    }
  }

  // this is done with a nested query so one can distinguish between the contact not owning subscription and there
  // simply being no delivery records, due to the hierarchical nature of the Salesforce response
  def deliveryRecordsQuery(
    contact: Contact,
    subscriptionNumber: String,
    optionalStartDate: Option[LocalDate],
    optionalEndDate: Option[LocalDate]
  ) =
    s"""SELECT (
       |    SELECT Delivery_Date__c, Delivery_Address__c, Delivery_Instructions__c, Has_Holiday_Stop__c, Address_Line_1__c,
       |           Address_Line_2__c, Address_Line_3__c, Address_Town__c, Address_Country__c, Address_Postcode__c,
       |           Case__c, Case__r.Id, Case__r.Subject, Case__r.Description, Case__r.Case_Closure_Reason__c
       |    FROM Delivery_Records__r
       |    ${deliveryDateFilter(optionalStartDate, optionalEndDate)}
       |    ORDER BY Delivery_Date__c DESC
       |)
       |FROM SF_Subscription__c WHERE Name = '${escapeString(subscriptionNumber)}'
       |                         AND ${contactToWhereClausePart(contact)}""".stripMargin

  def deliveryDateFilter(optionalStartDate: Option[LocalDate], optionalEndDate: Option[LocalDate]) = {
    List(
      optionalStartDate.map(startDate => s"Delivery_Date__c >= $startDate "),
      optionalEndDate.map(endDate => s"Delivery_Date__c <= $endDate")
    ).flatten match {
        case Nil => ""
        case nonEmpty => s" WHERE ${nonEmpty.mkString(" AND ")}"
      }
  }
}
