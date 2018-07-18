package com.gu.identityBackfill

import com.gu.util.resthttp.Types.ClientFailableOp
import com.gu.util.reader.Types._

object TypeConvert {

  implicit class TypeConvertClientOp[A](theEither: ClientFailableOp[A]) {
    def toApiGatewayOp = theEither.toDisjunction.toApiGatewayOp(_)
  }

}