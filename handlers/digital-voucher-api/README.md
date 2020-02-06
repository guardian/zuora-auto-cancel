Digital Voucher API
====================

This api provides access to Imovo's digital voucher services. This supports the provisioning and 
management of Imovo's cards used by customers to redeem subscriptions.

Usage
=====

All endpoints require...

- `x-api-key` header (generated by the `DigitalVoucherApiKey` in the CloudFormation)

| Method | Endpoint | Body | Response | Description |
| --- | --- | --- | --- | --- |
| GET | `/{STAGE}/digital-voucher/\<SALESFORCE_SUBSCRIPTION_ID\> | | {"cardCode":"\<Imovo Card Code\>","letterCode":"\<Imovo Letter Code\>"} | Returns the Imovo digital subscription associated with the salesforce subscription id |
| PUT | `/{STAGE}/digital-voucher/create/\<SALESFORCE_SUBSCRIPTION_ID\> | {"ratePlanName":"\<subscription rate plan name\>"} | {"cardCode":"\<Imovo Card Code\>","letterCode":"\<Imovo Letter Code\>"} | Creates an Imovo digital subscription or returns the details of the subscription if it already exists |
| PUT | `/{STAGE}/digital-voucher/replace/\<SALESFORCE_SUBSCRIPTION_ID\> | {"ratePlanName":"\<subscription rate plan name\>"} | {"cardCode":"\<Imovo Card Code\>","letterCode":"\<Imovo Letter Code\>"} | Forces an Imovo a new digital subscription invalidating the existing subscription associated with the salesforce subscription id |
| DELETE | `/{STAGE}/digital-voucher/\<SALESFORCE_SUBSCRIPTION_ID\> |  | | Deletes an Imovo digital subscription |