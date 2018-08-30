package com.gu.newproduct.api.addsubscription.email.voucher

import java.time.LocalDate
import com.gu.newproduct.TestData
import com.gu.newproduct.api.addsubscription.email.{DataExtensionName, ETPayload}
import com.gu.newproduct.api.addsubscription.zuora.CreateSubscription.SubscriptionName
import com.gu.newproduct.api.productcatalog.PlanId.VoucherSunday
import com.gu.newproduct.api.productcatalog.{Plan, PlanDescription}
import com.gu.util.apigateway.ApiGatewayResponse
import com.gu.util.reader.Types.ApiGatewayOp.{ContinueProcessing, ReturnWithResponse}
import org.scalatest.{AsyncFlatSpec, Matchers}
import scala.concurrent.Future

class SendConfirmationEmailVoucherTest extends AsyncFlatSpec with Matchers {
  it should "send voucher confirmation email" in {
    def sqsSend(payload: ETPayload[VoucherEmailData]): Future[Unit] = Future {
      payload shouldBe ETPayload("soldToEmail@mail.com", testVoucherData, DataExtensionName("paper-voucher"))
    }

    val send = SendConfirmationEmailVoucher(sqsSend, today) _
    send(testVoucherData).underlying map {
      result => result shouldBe ContinueProcessing(())
    }
  }

  it should "return error if contact has no email" in {

    val noEmailSoldTo = testVoucherData.contacts.soldTo.copy(email = None)
    val noSoldToEmailContacts = testVoucherData.contacts.copy(soldTo = noEmailSoldTo)
    val noSoldToEmailVoucherData = testVoucherData.copy(contacts = noSoldToEmailContacts)

    def sqsSend(payload: ETPayload[VoucherEmailData]): Future[Unit] = Future.successful(())

    val send = SendConfirmationEmailVoucher(sqsSend, today) _
    send(noSoldToEmailVoucherData).underlying map {
      result => result shouldBe ReturnWithResponse(ApiGatewayResponse.internalServerError("some error"))
    }
  }

  it should "return error if sqs send fails" in {

    def sqsSend(payload: ETPayload[VoucherEmailData]): Future[Unit] = Future.failed(new RuntimeException("sqs error"))

    val send = SendConfirmationEmailVoucher(sqsSend, today) _

    send(testVoucherData).underlying map {
      result => result shouldBe ReturnWithResponse(ApiGatewayResponse.internalServerError("some error"))
    }
  }

  def today = () => LocalDate.of(2018, 8, 24)

  val testVoucherData = VoucherEmailData(
    plan = Plan(VoucherSunday, PlanDescription("Sunday")),
    firstPaymentDate = LocalDate.of(2018, 9, 24),
    firstPaperDate = LocalDate.of(2018, 9, 23),
    subscriptionName = SubscriptionName("subName"),
    contacts = TestData.contacts,
    paymentMethod = TestData.directDebitPaymentMethod
  )

}
