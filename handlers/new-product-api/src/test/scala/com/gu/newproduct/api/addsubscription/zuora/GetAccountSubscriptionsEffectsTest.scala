package com.gu.newproduct.api.addsubscription.zuora

import com.gu.effects.{GetFromS3, RawEffects}
import com.gu.newproduct.api.addsubscription.ZuoraAccountId
import com.gu.newproduct.api.addsubscription.zuora.GetAccountSubscriptions.WireModel.ZuoraSubscriptionsResponse
import com.gu.newproduct.api.addsubscription.zuora.GetAccountSubscriptions.{Active, Subscription}
import com.gu.newproduct.api.productcatalog.ZuoraIds.ProductRatePlanId
import com.gu.test.EffectsTest
import com.gu.util.config.{LoadConfigModule, Stage}
import com.gu.util.zuora.{ZuoraRestConfig, ZuoraRestRequestMaker}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GetAccountSubscriptionsEffectsTest extends AnyFlatSpec with Matchers {

  it should "get payment details" taggedAs EffectsTest in {
    val actual = for {
      zuoraRestConfig <- LoadConfigModule(Stage("DEV"), GetFromS3.fetchString)[ZuoraRestConfig]
      zuoraDeps = ZuoraRestRequestMaker(RawEffects.response, zuoraRestConfig)
      res <- GetAccountSubscriptions(zuoraDeps.get[ZuoraSubscriptionsResponse])(ZuoraAccountId("2c92c0f860017cd501600893130317a7")).toDisjunction
    } yield {
      res
    }

    val expected = List(
      Subscription(
        status = Active,
        productRateplanIds = Set(ProductRatePlanId("2c92c0f852f2ebb20152f9269f067819"), ProductRatePlanId("2c92c0f84bbfec8b014bc655f4852d9d"))
      )
    )
    actual shouldBe Right(expected)
  }
}

