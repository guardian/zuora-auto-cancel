package com.gu.util.exacttarget

import com.gu.effects.sqs.AwsSQSSend.Payload
import com.gu.util.email._
import com.gu.util.reader.Types.ApiGatewayOp.ContinueProcessing
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.Json

class EmailSendStepsTest extends FlatSpec with Matchers {

  def makeMessage(recipient: String): EmailMessage = {
    EmailMessage(
      To = ToDef(
        Address = recipient,
        SubscriberKey = recipient,
        ContactAttributes = ContactAttributesDef(
          SubscriberAttributes = SubscriberAttributesDef(
            subscriber_id = "subIdValue",
            product = "productValue",
            payment_method = "paymentMethodValue",
            card_type = "cardTypeValue",
            card_expiry_date = "cardExpiryValue",
            first_name = "firstNameValue",
            last_name = "lastNameValue",
            primaryKey = PaymentId("paymentId"),
            price = "49.0 GBP",
            serviceStartDate = "31 January 2016",
            serviceEndDate = "31 January 2017"
          )
        )
      ),
      "dataExtensionName"
    )
  }

  "EmailSendSteps" should "serialise and send an email" in {
    var capturedPayload: Option[Payload] = None
    def sqsSend(payload: Payload): Unit = capturedPayload = Some(payload)

    EmailSendSteps(sqsSend)(makeMessage("james@jameson.com")) shouldBe ContinueProcessing(())
    Json.parse(capturedPayload.get.value) shouldBe Json.parse(
      """
        |{
        |  "To": {
        |    "Address": "james@jameson.com",
        |    "SubscriberKey": "james@jameson.com",
        |    "ContactAttributes": {
        |      "SubscriberAttributes": {
        |        "serviceEndDate": "31 January 2017",
        |        "first_name": "firstNameValue",
        |        "paymentId": "paymentId",
        |        "price": "49.0 GBP",
        |        "serviceStartDate": "31 January 2016",
        |        "subscriber_id": "subIdValue",
        |        "card_expiry_date": "cardExpiryValue",
        |        "payment_method": "paymentMethodValue",
        |        "last_name": "lastNameValue",
        |        "card_type": "cardTypeValue",
        |        "product": "productValue"
        |      }
        |    }
        |  },
        |  "DataExtensionName": "dataExtensionName"
        |}
      """.stripMargin
    )
  }

}
