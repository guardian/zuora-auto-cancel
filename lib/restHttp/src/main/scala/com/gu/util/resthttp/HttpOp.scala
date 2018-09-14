package com.gu.util.resthttp

import com.gu.util.resthttp.RestRequestMaker.filterIfSuccessful
import com.gu.util.resthttp.Types.{ClientFailableOp, ClientSuccess}
import okhttp3.{Request, Response}
import play.api.libs.json.{JsValue, Json, Reads}

case class HttpOp[PARAM, RESPONSE](
  inputToRequest: PARAM => Request,
  effect: Request => Response,
  responseToOutput: Response => ClientFailableOp[RESPONSE]
) {

  def map[NEWRESPONSE](toNewResponse: RESPONSE => NEWRESPONSE): HttpOp[PARAM, NEWRESPONSE] =
    HttpOp(inputToRequest, effect, responseToOutput.andThen(_.map(toNewResponse)))

  def flatMap[NEWRESPONSE](toNewResponse: RESPONSE => ClientFailableOp[NEWRESPONSE]): HttpOp[PARAM, NEWRESPONSE] =
    HttpOp(inputToRequest, effect, responseToOutput.andThen(_.flatMap(toNewResponse)))

  def runRequest(in: PARAM): ClientFailableOp[RESPONSE] =
    responseToOutput(effect(inputToRequest(in)))

  // this is effectively "contramap" so it is actually setting it up to run the given function before any previously setup functions
  // you can learn more about contravariant functors https://www.google.co.uk/search?q=contravariant+functor
  def setupRequest[UPDATEDPARAM](fromNewParam: UPDATEDPARAM => PARAM): HttpOp[UPDATEDPARAM, RESPONSE] =
    HttpOp(fromNewParam.andThen(inputToRequest), effect, responseToOutput)

}

object HttpOp {

  def apply(getResponse: Request => Response): HttpOp[Request, Response] =
    new HttpOp(identity, getResponse, response => ClientSuccess(response))

  // convenience, untuples for you
  implicit class HttpOpTuple2Ops[A1, A2, RESPONSE](httpOp2Arg: HttpOp[(A1, A2), RESPONSE]) {
    def runRequestMultiArg: (A1, A2) => ClientFailableOp[RESPONSE] = Function.untupled(httpOp2Arg.runRequest)
  }

  // convenience, tuples for you
  implicit class HttpOpOps[IN, RESPONSE](httpOp: HttpOp[IN, RESPONSE]) {
    def setupRequestMultiArg[A1, A2](function2Arg: (A1, A2) => IN): HttpOp[(A1, A2), RESPONSE] =
      httpOp.setupRequest(function2Arg.tupled)
  }

}

object RestOp {

  implicit class HttpOpParseOp[PARAM](httpOp: HttpOp[PARAM, JsValue]) {
    def parse[RESULT: Reads]: HttpOp[PARAM, RESULT] =
      httpOp.flatMap(a => RestRequestMaker.toResult(a))

  }

  def responseToJs(response: Response): ClientFailableOp[JsValue] = {
    filterIfSuccessful(response).map(response => Json.parse(response.body.string))
  }

}
