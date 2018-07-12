package com.gu.sf_contact_merge

import com.gu.sf_contact_merge.GetZuoraEmailsForAccounts.AccountId
import com.gu.util.zuora.RestRequestMaker.{ClientFailableOp, RequestsPUT}
import play.api.libs.json.{JsSuccess, Json, Reads}

object UpdateAccountSFLinks {

  case class Request(
    crmId: String,
    sfContactId__c: String
  )
  implicit val writes = Json.writes[Request]
  implicit val unitReads: Reads[Unit] = Reads(_ => JsSuccess(()))

  def apply(zuoraRequests: RequestsPUT)(crmId: String, sfContactId: String)(account: AccountId): ClientFailableOp[Unit] = {
    val request = Request(crmId, sfContactId)
    val path = s"accounts/${account.value}" // TODO danger - we shoudn't go building urls with string concatenation!
    zuoraRequests.put[Request, Unit](request, path)
  }

}