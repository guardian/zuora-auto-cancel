package com.gu.identityBackfill

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import com.gu.effects.TestingRawEffects
import com.gu.effects.TestingRawEffects.BasicRequest
import com.gu.identity.TestData
import com.gu.identityBackfill.EndToEndData._
import com.gu.util.apigateway.ApiGatewayHandler.LambdaIO
import org.scalatest.{Assertion, FlatSpec, Matchers}
import play.api.libs.json.Json

class EndToEndHandlerTest extends FlatSpec with Matchers {

  it should "manage an end to end call in dry run mode" in {

    val (responseString, requests): (String, List[TestingRawEffects.BasicRequest]) = getResultAndRequests(identityBackfillRequest(true))

    val expectedResponse =
      s"""
         |{"statusCode":"200","headers":{"Content-Type":"application/json"},"body":"Processing is not required: DRY RUN requested! skipping to the end"}
         |""".stripMargin
    responseString jsonMatches expectedResponse
    requests should be(List(BasicRequest("GET", "/user?emailAddress=email@address", "")))
  }

  it should "manage an end to end call" in {

    val (responseString, requests): (String, List[TestingRawEffects.BasicRequest]) = getResultAndRequests(identityBackfillRequest(false))

    val expectedResponse =
      s"""
         |{"statusCode":"500","headers":{"Content-Type":"application/json"},"body":"Failed to process event due to the following error: todo"}
         |""".stripMargin
    responseString jsonMatches expectedResponse
    requests should be(List(BasicRequest("GET", "/user?emailAddress=email@address", "")))
  }

  def getResultAndRequests(input: String): (String, List[TestingRawEffects.BasicRequest]) = {
    val stream = new ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    val os = new ByteArrayOutputStream()
    val config = new TestingRawEffects(false, 200, responses)

    //execute
    Handler.runWithEffects(config.rawEffects, LambdaIO(stream, os, null))

    val responseString = new String(os.toByteArray, "UTF-8")

    (responseString, config.requestsAttempted)
  }

}

object Runner {

}

object EndToEndData {

  implicit class JsonMatcher(private val actual: String) {
    import Matchers._
    def jsonMatches(expected: String): Assertion = {
      val expectedJson = Json.parse(expected)
      val actualJson = Json.parse(actual)
      actualJson should be(expectedJson)
    }
  }

  def responses: Map[String, (Int, String)] = Map("/user?emailAddress=email@address" -> ((200, TestData.dummyIdentityResponse)))

  def identityBackfillRequest(dryRun: Boolean): String =
    s"""
      |{
      |    "resource": "/payment-failure",
      |    "path": "/payment-failure",
      |    "httpMethod": "POST",
      |    "headers": {
      |        "CloudFront-Forwarded-Proto": "https",
      |        "CloudFront-Is-Desktop-Viewer": "true",
      |        "CloudFront-Is-Mobile-Viewer": "false",
      |        "CloudFront-Is-SmartTV-Viewer": "false",
      |        "CloudFront-Is-Tablet-Viewer": "false",
      |        "CloudFront-Viewer-Country": "US",
      |        "Content-Type": "application/json; charset=utf-8",
      |        "Host": "hosthosthost",
      |        "User-Agent": "Amazon CloudFront",
      |        "Via": "1.1 c154e1d9f76106d9025a8ffb4f4831ae.cloudfront.net (CloudFront), 1.1 11b20299329437ea4e28ea2b556ea990.cloudfront.net (CloudFront)",
      |        "X-Amz-Cf-Id": "hihi",
      |        "X-Amzn-Trace-Id": "Root=1-5a0f2574-4cb4d1534b9f321a3b777624",
      |        "X-Forwarded-For": "1.1.1.1, 1.1.1.1",
      |        "X-Forwarded-Port": "443",
      |        "X-Forwarded-Proto": "https"
      |    },
      |    "queryStringParameters": {
      |        "apiClientId": "a",
      |        "apiToken": "b"
      |    },
      |    "pathParameters": null,
      |    "stageVariables": null,
      |    "requestContext": {
      |        "path": "/CODE/payment-failure",
      |        "accountId": "865473395570",
      |        "resourceId": "ls9b61",
      |        "stage": "CODE",
      |        "requestId": "11111111-cbc2-11e7-a389-b7e6e2ab8316",
      |        "identity": {
      |            "cognitoIdentityPoolId": null,
      |            "accountId": null,
      |            "cognitoIdentityId": null,
      |            "caller": null,
      |            "apiKey": "",
      |            "sourceIp": "1.1.1.1",
      |            "accessKey": null,
      |            "cognitoAuthenticationType": null,
      |            "cognitoAuthenticationProvider": null,
      |            "userArn": null,
      |            "userAgent": "Amazon CloudFront",
      |            "user": null
      |        },
      |        "resourcePath": "/payment-failure",
      |        "httpMethod": "POST",
      |        "apiId": "11111"
      |    },
      |    "body": "{\\"emailAddress\\": \\"email@address\\", \\"dryRun\\": $dryRun}",
      |    "isBase64Encoded": false
      |}
    """.stripMargin

}
