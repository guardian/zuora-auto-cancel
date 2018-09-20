package com.gu.sf_contact_merge.getsfcontacts

import com.gu.effects.{GetFromS3, RawEffects}
import com.gu.salesforce.auth.SalesforceAuthenticate
import com.gu.salesforce.auth.SalesforceAuthenticate.SFAuthConfig
import com.gu.salesforce.dev.SFEffectsData
import com.gu.sf_contact_merge.getsfcontacts.GetSfAddress.{IsDigitalVoucherUser, SFAddress, SFContact, UsableContactAddress}
import com.gu.sf_contact_merge.getsfcontacts.GetSfAddress.SFAddressFields._
import com.gu.test.EffectsTest
import com.gu.util.config.{LoadConfigModule, Stage}
import org.scalatest.{FlatSpec, Matchers}
import scalaz.\/-

class GetSfAddressEffectsTest extends FlatSpec with Matchers {

  it should "get a contact" taggedAs EffectsTest in {

    val testContact = SFEffectsData.testContactHasNamePhoneOtherAddress

    val actual = for {
      sfConfig <- LoadConfigModule(Stage("DEV"), GetFromS3.fetchString)[SFAuthConfig]
      response = RawEffects.response
      sfAuth <- SalesforceAuthenticate.doAuth(response, sfConfig).toDisjunction
      get = SalesforceAuthenticate.get(response, sfAuth)
      getSfAddress = GetSfAddress(get)
      address <- getSfAddress.apply(testContact).value.toDisjunction
    } yield address

    val expected = SFContact(
      UsableContactAddress(SFAddress(
        SFStreet("123 dayone street"),
        Some(SFCity("city1")),
        Some(SFState("state1")),
        Some(SFPostalCode("postal1")),
        SFCountry("Afghanistan"),
        Some(SFPhone("012345"))
      )),
      IsDigitalVoucherUser(false)
    )

    actual should be(\/-(expected))

  }

}
