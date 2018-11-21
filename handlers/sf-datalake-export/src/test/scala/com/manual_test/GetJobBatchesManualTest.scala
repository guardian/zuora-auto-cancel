package com.manual_test

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import com.gu.sf_datalake_export.handlers.GetBatchesHandler

//This is just a way to locally run the lambda in dev
object GetJobBatchesManualTest extends App {
  val request =
    """{
      |"jobId" : "7506E000004FloxQAC",
      |"jobName" : "Contact_2018-11-20",
      |"objectName" : "Contact",
      |"uploadToDataLake" : false
      |}
    """.stripMargin

  println(s"sending request..")
  println(request)

  val testInputStream = new ByteArrayInputStream(request.getBytes)
  val testOutput = new ByteArrayOutputStream()
  GetBatchesHandler(testInputStream, testOutput, null)

  val response = new String(testOutput.toByteArray)
  println(response)
}