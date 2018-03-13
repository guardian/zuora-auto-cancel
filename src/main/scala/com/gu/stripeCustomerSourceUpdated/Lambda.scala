package com.gu.stripeCustomerSourceUpdated

import java.io.{ InputStream, OutputStream }

import com.amazonaws.services.lambda.runtime.Context
import com.gu.effects.RawEffects
import com.gu.stripeCustomerSourceUpdated.SourceUpdatedSteps.Deps
import com.gu.util.Config
import com.gu.util.apigateway.ApiGatewayHandler
import com.gu.util.zuora.ZuoraRestConfig

object Lambda {

  case class LambdaDeps(handler: (InputStream, OutputStream, Context) => Unit)

  def default(rawEffects: RawEffects) =
    ApiGatewayHandler(rawEffects.stage, rawEffects.s3Load, { config: Config[ZuoraRestConfig] =>
      SourceUpdatedSteps(Deps.default(rawEffects.response, config))
    })

  // this is the entry point
  // it's referenced by the cloudformation so make sure you keep it in step
  // it's the only part you can't test of the handler
  def apply(inputStream: InputStream, outputStream: OutputStream, context: Context): Unit = {
    val deps = default(RawEffects.createDefault)
    deps(inputStream, outputStream, context)
  }

}