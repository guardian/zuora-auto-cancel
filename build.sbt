import Dependencies._

val scalaSettings = Seq(
  ThisBuild / scalaVersion := "2.13.3",
  version      := "0.0.1",
  organization := "com.gu",
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding", "UTF-8",
    "-feature",
    "-target:jvm-1.8",
    "-language:existentials",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-unchecked",
    "-Xlint",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard"
  ),
  fork in Test := true,
  autoCompilerPlugins := true,
  resolvers += "Guardian Platform Bintray" at "https://dl.bintray.com/guardian/platforms"
)

// fixme this whole file needs splitting down appropriately

lazy val EffectsTest = config("effectsTest") extend(Test) describedAs("run the edge tests")
lazy val HealthCheckTest = config("healthCheck") extend(Test) describedAs("run the health checks against prod/code")
val testSettings = inConfig(EffectsTest)(Defaults.testTasks) ++ inConfig(HealthCheckTest)(Defaults.testTasks) ++ Seq(
  testOptions in Test += Tests.Argument("-l", "com.gu.test.EffectsTest"),
  testOptions in Test += Tests.Argument("-l", "com.gu.test.HealthCheck"),

  testOptions in EffectsTest -= Tests.Argument("-l", "com.gu.test.EffectsTest"),
  testOptions in EffectsTest -= Tests.Argument("-l", "com.gu.test.HealthCheck"),
  testOptions in EffectsTest += Tests.Argument("-n", "com.gu.test.EffectsTest"),

  testOptions in HealthCheckTest -= Tests.Argument("-l", "com.gu.test.EffectsTest"),
  testOptions in HealthCheckTest -= Tests.Argument("-l", "com.gu.test.HealthCheck"),
  testOptions in HealthCheckTest += Tests.Argument("-n", "com.gu.test.HealthCheck")
)

def library(theProject: Project) = theProject.settings(scalaSettings, testSettings).configs(EffectsTest, HealthCheckTest)

// ==== START libraries ====

lazy val test = library(project in file("lib/test"))
  .settings(
    libraryDependencies ++= Seq(
      scalatest,
      playJson % "test"
    )
  )

val testDep = test % "test->test"

lazy val zuora = library(project in file("lib/zuora"))
  .dependsOn(
    restHttp,
    testDep,
    handler,// TODO only for the config, which needs to be split out
    effects % "test->test"
  )
  .settings(
    libraryDependencies ++= Seq(okhttp3, playJson, scalatest) ++ logging
  )

lazy val `salesforce-core` = library(project in file("lib/salesforce/core"))
  .dependsOn(`config-core`)
  .settings(
    libraryDependencies ++= Seq(playJson) ++ logging
  )

lazy val `salesforce-client` = library(project in file("lib/salesforce/client"))
  .dependsOn(
    restHttp,
    handler,// % "test->test" TODO make this dep only in test - SF client shouldn't depends on ApiGateway
    effects % "test->test",
    testDep,
    `salesforce-core`
  )
  .settings(
    libraryDependencies ++= Seq(okhttp3, catsCore, playJson, scalatest) ++ logging
  )

lazy val `salesforce-sttp-client` = library(project in file("lib/salesforce/sttp-client"))
  .dependsOn(
    `salesforce-core`,
    `salesforce-sttp-test-stub` % Test
  )
  .settings(
    libraryDependencies ++=
      Seq(sttp, sttpCirce, sttpCats % Test, scalatest, catsCore, catsEffect, circe) ++ logging
  )

lazy val `salesforce-sttp-test-stub` = library(project in file("lib/salesforce/sttp-test-stub"))
  .dependsOn(
    `salesforce-core`
  )
  .settings(
    libraryDependencies ++= Seq(sttp, sttpCirce, scalatest) ++ logging
  )

lazy val `holiday-stops` = library(project in file("lib/holiday-stops"))
  .dependsOn(
    `salesforce-client`,
    effects % "test->test",
    testDep,
    `zuora-core` % "compile->compile;test->test"
  )
  .settings(
    libraryDependencies ++= Seq(
      okhttp3,
      playJson,
      scalatest,
      scalaCheck,
      playJsonExtensions,
      circe,
      circeParser,
      sttp,
      sttpCirce,
      enumeratum,
      zio
    ) ++ logging
  )

lazy val restHttp = library(project in file("lib/restHttp"))
  .dependsOn(handler)
  .settings(
    libraryDependencies ++= Seq(okhttp3, catsCore, playJson, scalatest) ++ logging
  )

lazy val s3ConfigValidator = library(project in file("lib/s3ConfigValidator"))
  .dependsOn(
    testDep,
    handler,
    zuora,
    `digital-subscription-expiry`,
    `identity-backfill`,
    effectsDepIncludingTestFolder,
    `cancellation-sf-cases-api`,
    `sf-gocardless-sync`,
    `holiday-stop-api`
  )
  .settings(
    libraryDependencies ++= Seq(scalatest)
  )

lazy val handler = library(project in file("lib/handler"))
  .dependsOn(`effects-s3`, `config-core`)
  .settings(
    libraryDependencies ++= Seq(okhttp3, catsCore, playJson, scalatest, awsLambda) ++ logging
  )

// to aid testability, only the actual handlers called as a lambda can depend on this
lazy val effects = library(project in file("lib/effects"))
  .dependsOn(handler)
  .settings(
    libraryDependencies ++= Seq(okhttp3, playJson, scalatest, awsS3) ++ logging
  )
lazy val `effects-s3` = library(project in file("lib/effects-s3"))
  .settings(
    libraryDependencies ++= Seq(awsS3) ++ logging
  )
lazy val `effects-sqs` = library(project in file("lib/effects-sqs"))
  .dependsOn(testDep)
  .settings(
    libraryDependencies ++= Seq(awsSQS) ++ logging
  )
lazy val `effects-lambda` = library(project in file("lib/effects-lambda"))
  .dependsOn(testDep)
  .settings(
    libraryDependencies ++= Seq(awsSdkLambda) ++ logging
  )

lazy val `config-core` = library(project in file("lib/config-core"))

lazy val `config-cats` = library(project in file("lib/config-cats"))
  .settings(
    libraryDependencies ++= Seq(simpleConfig, catsEffect, circe, circeConfig)
  )

val effectsDepIncludingTestFolder: ClasspathDependency = effects % "compile->compile;test->test"

lazy val `zuora-reports` = library(project in file("lib/zuora-reports"))
  .dependsOn(zuora, handler, effectsDepIncludingTestFolder, testDep)

lazy val `fulfilment-dates` = library(project in file("lib/fulfilment-dates"))
  .dependsOn(`effects-s3`, `config-core`, testDep, `zuora-core`)
  .settings(
    libraryDependencies ++= Seq(catsCore, circe, circeParser)
  )

lazy val `zuora-core` = library(project in file("lib/zuora-core"))
  .settings(
    libraryDependencies ++= Seq(
      playJson,
      playJsonExtensions,
      catsCore,
      circe,
      circeParser,
      sttp,
      sttpCirce,
      scalatest,
      diffx,
    ) ++ logging
  )

lazy val `credit-processor` = library(project in file("lib/credit-processor"))
  .dependsOn(
    `zuora-core`,
    `fulfilment-dates`
  ).settings(
    libraryDependencies ++= logging
  )

lazy val `imovo-sttp-client` = library(project in file("lib/imovo/imovo-sttp-client"))
  .settings(
    libraryDependencies ++=
      Seq(sttp, sttpCirce, sttpCats % Test, scalatest, catsCore, catsEffect, circe) ++ logging
  )

lazy val `imovo-sttp-test-stub` = library(project in file("lib/imovo/imovo-sttp-test-stub"))
  .dependsOn(`imovo-sttp-client`)
  .settings(
    libraryDependencies ++= Seq(scalatest)
  )

def lambdaProject(projectName: String, projectDescription: String, dependencies: Seq[sbt.ModuleID] = Nil, isCdk: Boolean = false) = {
  val cfName = if (isCdk) "cdk-cfn.yaml" else "cfn.yaml"
  Project(projectName, file(s"handlers/$projectName"))
    .enablePlugins(RiffRaffArtifact)
    .configs(EffectsTest, HealthCheckTest)
    .settings(scalaSettings, testSettings)
    .settings(
      name := projectName,
      description:= projectDescription,
      assemblyJarName := s"$projectName.jar",
      assemblyMergeStrategyDiscardModuleInfo,
      riffRaffPackageType := assembly.value,
      riffRaffUploadArtifactBucket := Option("riffraff-artifact"),
      riffRaffUploadManifestBucket := Option("riffraff-builds"),
      riffRaffManifestProjectName := s"support-service-lambdas::$projectName",
      riffRaffArtifactResources += (file(s"handlers/$projectName/$cfName"), s"cfn/$cfName"),
      libraryDependencies ++= dependencies ++ logging
    )
}

// FIXME: This seems to be non-standard
// FIXME: Why is the name in sub-project build.sbt support-service-lambda
// FIXME: Why is riff-raff not refering to CF?
lazy val `zuora-callout-apis` = library(project in file("handlers/zuora-callout-apis"))
  .enablePlugins(RiffRaffArtifact)
  .dependsOn(zuora, handler, effectsDepIncludingTestFolder, `effects-sqs`, testDep)

lazy val `identity-backfill` = lambdaProject("identity-backfill", "links subscriptions with identity accounts", Seq(supportInternationalisation)).dependsOn(
  zuora,
  `salesforce-client` % "compile->compile;test->test",
  handler,
  effectsDepIncludingTestFolder,
  testDep
)

lazy val `digital-subscription-expiry` = lambdaProject(
  "digital-subscription-expiry",
  "check digital subscription expiration for authorisation purposes",
  Seq(contentAuthCommon)
).dependsOn(zuora, handler, effectsDepIncludingTestFolder, testDep)

lazy val `catalog-service` = lambdaProject(
  "catalog-service",
  "Download the Zuora Catalog and store the JSON in S3"
).dependsOn(zuora, handler, effectsDepIncludingTestFolder, testDep)

lazy val `identity-retention` = lambdaProject(
  "identity-retention",
  "Confirms whether an identity account can be deleted, from a reader revenue perspective"
).dependsOn(zuora, handler, effectsDepIncludingTestFolder, testDep)

lazy val `new-product-api` = lambdaProject(
  "new-product-api",
  "Add subscription to account",
  Seq(supportInternationalisation)
).dependsOn(zuora, handler, `effects-sqs`, effectsDepIncludingTestFolder, testDep)

lazy val `zuora-retention` = lambdaProject(
  "zuora-retention",
  "find and mark accounts that are out of the retention period"
).dependsOn(`zuora-reports`, handler, effectsDepIncludingTestFolder, testDep)

lazy val `zuora-sar` = lambdaProject(
  "zuora-sar",
  "Performs a Subject Access Requests against Zuora",
  Seq(catsEffect, circeParser, circe)
).dependsOn(`zuora-reports`, handler, effectsDepIncludingTestFolder, testDep, `effects-s3`, `effects-lambda`)

lazy val `dev-env-cleaner` = lambdaProject(
  "dev-env-cleaner",
  "Cleans up the salesforce to free up storage via 360 sync/zuora",
  Seq(awsCloudwatch, catsEffect, circeParser, circe)
).dependsOn(`zuora-reports`, handler, effectsDepIncludingTestFolder, testDep, `effects-s3`, `effects-lambda`)

lazy val `sf-contact-merge` = lambdaProject(
  "sf-contact-merge",
  "Merges together the salesforce account referenced by a set of zuora accounts"
).dependsOn(zuora, `salesforce-client` % "compile->compile;test->test", handler, effectsDepIncludingTestFolder, testDep)

lazy val `sf-billing-account-remover` = lambdaProject(
  "sf-billing-account-remover",
  "Removes Billing Accounts and related records from Salesforce",
  Seq(circe, circeParser, scalajHttp))

lazy val `sf-api-user-credentials-setter` = lambdaProject(
  "sf-api-user-credentials-setter",
  "Set passwords for Aws API Users in SF, and then create or update an entry for the credentials in AWS secrets manager",
  Seq(awsSecretsManager, circe, circeParser, scalajHttp, awsS3))

lazy val `cancellation-sf-cases-api` = lambdaProject(
  "cancellation-sf-cases-api",
  "Create/update SalesForce cases for self service cancellation tracking",
  Seq(playJsonExtensions)
).dependsOn(`salesforce-client`, handler, effectsDepIncludingTestFolder, testDep)

lazy val `sf-gocardless-sync` = lambdaProject(
  "sf-gocardless-sync",
  "Polls GoCardless for direct debit mandate events and pushes into SalesForce",
  Seq(playJsonExtensions)
).dependsOn(`salesforce-client`, handler, effectsDepIncludingTestFolder, testDep)

lazy val `holiday-stop-api` = lambdaProject(
  "holiday-stop-api",
  "CRUD API for Holiday Stop Requests stored in SalesForce",
  Seq(playJsonExtensions)
).dependsOn(`holiday-stops` % "compile->compile;test->test", handler, effectsDepIncludingTestFolder, testDep, `fulfilment-dates`)

lazy val `sf-datalake-export` = lambdaProject(
  "sf-datalake-export",
  "Export salesforce data to the data lake",
  Seq(scalaXml)
).dependsOn(`salesforce-client`, handler, effectsDepIncludingTestFolder, testDep)

lazy val `zuora-datalake-export` = lambdaProject(
  "zuora-datalake-export",
  "Zuora to Datalake export using Stateful AQuA API which exports incremental changes",
  Seq(scalaLambda, scalajHttp, awsS3, enumeratum))

lazy val `batch-email-sender` = lambdaProject(
  "batch-email-sender",
  "Receive batches of emails to be sent, munge them into an appropriate format and put them on the email sending queue.",
  Seq(playJsonExtensions, supportInternationalisation, diffx)
).dependsOn(handler, `effects-sqs`, effectsDepIncludingTestFolder, testDep)

lazy val `holiday-stop-processor` = lambdaProject(
  "holiday-stop-processor",
  "Add a holiday credit amendment to a subscription.",
  Seq(scalaLambda, awsS3)
).dependsOn(
  `credit-processor`,
  `holiday-stops` % "compile->compile;test->test",
  effects
)

lazy val `delivery-problem-credit-processor` = lambdaProject(
  "delivery-problem-credit-processor",
  "Applies a credit amendment to a subscription for a delivery problem.",
  Seq(
    scalaLambda,
    circe,
    zio,
    sttpAsyncHttpClientBackendCats,
    scalatest,
    diffx
  )
).dependsOn(`credit-processor`, `salesforce-sttp-client`, effects)

lazy val `metric-push-api` = lambdaProject(
  "metric-push-api",
  "HTTP API to push a metric to cloudwatch so we can alarm on errors")

lazy val `sf-move-subscriptions-api` = lambdaProject(
  "sf-move-subscriptions-api",
  "API for for moving subscriptions in ZUORA from SalesForce",
  Seq(
    http4sDsl,
    http4sCirce,
    http4sServer,
    sttp,
    sttpCirce,
    sttpAsyncHttpClientBackendCats,
    scalatest,
    diffx
  ),
  isCdk = true
).dependsOn(`effects-s3`, `config-cats`, `zuora-core`, `http4s-lambda-handler`)

lazy val `fulfilment-date-calculator` = lambdaProject(
  "fulfilment-date-calculator",
  "Generate files in S3 bucket containing relevant fulfilment-related dates, for example, acquisitionsStartDate, holidayStopFirstAvailableDate, etc.",
  Seq(scalaLambda, scalajHttp, enumeratum)
).dependsOn(testDep, `fulfilment-dates`)

lazy val `delivery-records-api` = lambdaProject(
  "delivery-records-api",
  "API for accessing delivery records in Salesforce",
  Seq(http4sDsl, http4sCirce, http4sServer, circe, sttpAsyncHttpClientBackendCats, scalatest)
).dependsOn(
  `effects-s3`,
  `config-core`,
  `salesforce-sttp-client`,
  `salesforce-sttp-test-stub` % Test,
  `http4s-lambda-handler`
)

lazy val `digital-voucher-api` = lambdaProject(
  "digital-voucher-api",
  "API for integrating Imovos digital voucher services",
  Seq(
    http4sDsl,
    http4sCirce,
    http4sServer,
    sttpAsyncHttpClientBackendCats,
    scalatest,
    diffx,
    scalaMock,
  )
).dependsOn(`effects-s3`, `config-cats`, `imovo-sttp-client`, `imovo-sttp-test-stub` % Test, `http4s-lambda-handler`)

lazy val `digital-voucher-cancellation-processor` = lambdaProject(
  "digital-voucher-cancellation-processor",
  "Processor that co-ordinates the cancellation of digital voucher redemption via the imovo api",
  Seq(
    scalatest,
    diffx,
    sttpCats
  ),
  isCdk = true
).dependsOn(
  `config-cats`,
  `salesforce-sttp-client`,
  `salesforce-sttp-test-stub` % Test,
  `imovo-sttp-client`,
  `imovo-sttp-test-stub` % Test
)

lazy val `digital-voucher-suspension-processor` = lambdaProject(
  "digital-voucher-suspension-processor",
  "Processor that suspends digital vouchers via the digital voucher API.",
  Seq(
    awsLambda,
    sttpAsyncHttpClientBackendCats,
    sttpOkhttpBackend,
    scalatest,
    scalaMock
  )
).dependsOn(`salesforce-sttp-client`, `imovo-sttp-client`)

lazy val `contact-us-api` = lambdaProject(
  "contact-us-api",
  "Transforms a request from the Contact Us form into a Salesforce case",
  Seq(
    circe,
    circeParser,
    scalatest,
    scalajHttp,
    awsEvents
  )
).dependsOn(handler)

lazy val `http4s-lambda-handler` = library(project in file("lib/http4s-lambda-handler"))
  .settings(
    libraryDependencies ++= Seq(circe, circeParser, http4sCore, http4sDsl % Test, scalatest) ++ logging
  )


// ==== END handlers ====

initialize := {
  val _ = initialize.value
  assert(List("1.8", "11").contains(sys.props("java.specification.version")),
    "Java 8 or 11 is required for this project.")
}

lazy val deployAwsLambda = inputKey[Unit]("Directly update AWS lambda code from DEV instead of via RiffRaff for faster feedback loop")
deployAwsLambda := {
  import scala.sys.process._
  import complete.DefaultParsers._
  val Seq(name, stage) = spaceDelimited("<arg>").parsed
  s"aws lambda update-function-code --function-name $name-$stage --zip-file fileb://handlers/$name/target/scala-2.13/$name.jar --profile membership --region eu-west-1".!
}

// run from root project: deploy holiday-stop-processor CODE
commands += Command.args("deploy", "<name stage>") { (state, args) =>
  val Seq(name, stage) = args
  s"""$name/assembly""":: s"deployAwsLambda $name $stage" :: state
}
