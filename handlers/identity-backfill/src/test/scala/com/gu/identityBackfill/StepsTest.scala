package com.gu.identityBackfill

import com.gu.identityBackfill.StepsData._
import com.gu.identityBackfill.Types.{IdentityId, _}
import com.gu.util.apigateway.{ApiGatewayRequest, ApiGatewayResponse}
import com.gu.util.reader.Types.FailableOp
import org.scalatest.{FlatSpec, Matchers}

import scalaz.{-\/, \/-}

class StepsTest extends FlatSpec with Matchers {

  class StepsWithMocks {

    var zuoraUpdate: Option[(AccountId, IdentityId)] = None // !!
    var salesforceUpdate: Option[(SFContactId, IdentityId)] = None // !!

    def getSteps(
      noOfAccountWithSameId: Int = 0,
      numberOfZuoraAccountsForEmail: Int = 1
    ): ApiGatewayRequest => FailableOp[Unit] = IdentityBackfillSteps(
      getByEmail = _ => \/-(IdentityId("asdf")),
      getZuoraAccountsForEmail = _ => \/-(List.fill(numberOfZuoraAccountsForEmail)(ZuoraAccountIdentitySFContact(AccountId("acc"), IdentityId(""), SFContactId("sf")))),
      countZuoraAccountsForIdentityId = _ => \/-(noOfAccountWithSameId),
      updateZuoraIdentityId = (accountId, identityId) => {
        zuoraUpdate = Some((accountId, identityId))
        \/-(())
      },
      updateSalesforceIdentityId = (sFContactId, identityId) => {
        salesforceUpdate = Some((sFContactId, identityId))
        \/-(())
      }
    ).steps

  }

  it should "go through a happy case in real mode" in {

    val stepsWithMocks = new StepsWithMocks
    import stepsWithMocks._

    val result =
      getSteps()(ApiGatewayRequest(None, identityBackfillRequest(false), None))

    val expectedResult = \/-(())
    result should be(expectedResult)
    zuoraUpdate should be(Some((AccountId("acc"), IdentityId("asdf"))))
    salesforceUpdate should be(Some((SFContactId("sf"), IdentityId("asdf"))))
  }

  it should "go through a happy case in dry run mode without calling update" in {

    val stepsWithMocks = new StepsWithMocks
    import stepsWithMocks._

    val result =
      getSteps()(ApiGatewayRequest(None, identityBackfillRequest(true), None))

    val expectedResult = -\/(ApiGatewayResponse.noActionRequired("DRY RUN requested! skipping to the end"))
    result should be(expectedResult)
    zuoraUpdate should be(None)
    salesforceUpdate should be(None)
  }

  it should "go through a already got identity case without calling update even not in dry run mode" in {

    val stepsWithMocks = new StepsWithMocks
    import stepsWithMocks._

    val result =
      getSteps(noOfAccountWithSameId = 1)(ApiGatewayRequest(None, identityBackfillRequest(false), None))

    val expectedResult = -\/(ApiGatewayResponse.notFound("already used that identity id"))
    result should be(expectedResult)
    zuoraUpdate should be(None)
    salesforceUpdate should be(None)
  }

  it should "go through a multiple zuora account without calling update even not in dry run mode" in {

    val stepsWithMocks = new StepsWithMocks
    import stepsWithMocks._

    val result =
      getSteps(numberOfZuoraAccountsForEmail = 2)(ApiGatewayRequest(None, identityBackfillRequest(false), None))

    val expectedResult = -\/(ApiGatewayResponse.notFound("should have exactly one zuora account per email at this stage"))
    result should be(expectedResult)
    zuoraUpdate should be(None)
    salesforceUpdate should be(None)
  }

}

object StepsData {

  def identityBackfillRequest(dryRun: Boolean): String =
    s"""{"emailAddress": "email@address", "dryRun": $dryRun}""""

}
