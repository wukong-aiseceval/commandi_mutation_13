/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.streaming

import java.io.File

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.{DirectoryFileFilter, RegexFileFilter}

import org.apache.spark.sql.{ContinuousQuery, Row, StreamTest}
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.execution.datasources.parquet
import org.apache.spark.sql.execution.streaming.{FileStreamSinkWriter, MemoryStream}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.util.Utils

class FileStreamSinkSuite extends StreamTest with SharedSQLContext {
  import testImplicits._


  test("FileStreamSinkWriter - unpartitioned data") {
    val path = Utils.createTempDir()
    path.delete()

    val hadoopConf = sqlContext.sparkContext.hadoopConfiguration
    val fileFormat = new parquet.DefaultSource()

    def writeRange(start: Int, end: Int, numPartitions: Int): Seq[String] = {
      val df = sqlContext
        .range(start, end, 1, numPartitions)
        .select($"id", lit(100).as("data"))
      val writer = new FileStreamSinkWriter(
        df, fileFormat, path.toString, partitionColumnNames = Nil, hadoopConf, Map.empty)
      writer.write().map(_.path.stripPrefix("file://"))
    }

    // Write and check whether new files are written correctly
    val files1 = writeRange(0, 10, 2)
    assert(files1.size === 2, s"unexpected number of files: $files1")
    checkFilesExist(path, files1, "file not written")
    checkAnswer(sqlContext.read.load(path.getCanonicalPath), (0 until 10).map(Row(_, 100)))

    // Append and check whether new files are written correctly and old files still exist
    val files2 = writeRange(10, 20, 3)
    assert(files2.size === 3, s"unexpected number of files: $files2")
    assert(files2.intersect(files1).isEmpty, "old files returned")
    checkFilesExist(path, files2, s"New file not written")
    checkFilesExist(path, files1, s"Old file not found")
    checkAnswer(sqlContext.read.load(path.getCanonicalPath), (0 until 20).map(Row(_, 100)))
  }

  test("FileStreamSinkWriter - partitioned data") {
    implicit val e = ExpressionEncoder[java.lang.Long]
    val path = Utils.createTempDir()
    path.delete()

    val hadoopConf = sqlContext.sparkContext.hadoopConfiguration
    val fileFormat = new parquet.DefaultSource()

    def writeRange(start: Int, end: Int, numPartitions: Int): Seq[String] = {
      val df = sqlContext
        .range(start, end, 1, numPartitions)
        .flatMap(x => Iterator(x, x, x)).toDF("id")
        .select($"id", lit(100).as("data1"), lit(1000).as("data2"))

      require(df.rdd.partitions.size === numPartitions)
      val writer = new FileStreamSinkWriter(
        df, fileFormat, path.toString, partitionColumnNames = Seq("id"), hadoopConf, Map.empty)
      writer.write().map(_.path.stripPrefix("file://"))
    }

    def checkOneFileWrittenPerKey(keys: Seq[Int], filesWritten: Seq[String]): Unit = {
      keys.foreach { id =>
        assert(
          filesWritten.count(_.contains(s"/id=$id/")) == 1,
          s"no file for id=$id. all files: \n\t${filesWritten.mkString("\n\t")}"
        )
      }
    }

    // Write and check whether new files are written correctly
    val files1 = writeRange(0, 10, 2)
    assert(files1.size === 10, s"unexpected number of files:\n${files1.mkString("\n")}")
    checkFilesExist(path, files1, "file not written")
    checkOneFileWrittenPerKey(0 until 10, files1)

    val answer1 = (0 until 10).flatMap(x => Iterator(x, x, x)).map(Row(100, 1000, _))
    checkAnswer(sqlContext.read.load(path.getCanonicalPath), answer1)

    // Append and check whether new files are written correctly and old files still exist
    val files2 = writeRange(0, 20, 3)
    assert(files2.size === 20, s"unexpected number of files:\n${files2.mkString("\n")}")
    assert(files2.intersect(files1).isEmpty, "old files returned")
    checkFilesExist(path, files2, s"New file not written")
    checkFilesExist(path, files1, s"Old file not found")
    checkOneFileWrittenPerKey(0 until 20, files2)

    val answer2 = (0 until 20).flatMap(x => Iterator(x, x, x)).map(Row(100, 1000, _))
    checkAnswer(sqlContext.read.load(path.getCanonicalPath), answer1 ++ answer2)
  }

  test("FileStreamSink - unpartitioned writing and batch reading") {
    val inputData = MemoryStream[Int]
    val df = inputData.toDF()

    val outputDir = Utils.createTempDir(namePrefix = "stream.output").getCanonicalPath
    val checkpointDir = Utils.createTempDir(namePrefix = "stream.checkpoint").getCanonicalPath

    var query: ContinuousQuery = null

    try {
      query =
        df.write
          .format("parquet")
          .option("checkpointLocation", checkpointDir)
          .startStream(outputDir)

      inputData.addData(1, 2, 3)

      failAfter(streamingTimeout) {
        query.processAllAvailable()
      }

      val outputDf = sqlContext.read.parquet(outputDir).as[Int]
      checkDataset(outputDf, 1, 2, 3)

    } finally {
      if (query != null) {
        query.stop()
      }
    }
  }

  test("FileStreamSink - partitioned writing and batch reading [IGNORES PARTITION COLUMN]") {
    val inputData = MemoryStream[Int]
    val ds = inputData.toDS()

    val outputDir = Utils.createTempDir(namePrefix = "stream.output").getCanonicalPath
    val checkpointDir = Utils.createTempDir(namePrefix = "stream.checkpoint").getCanonicalPath

    var query: ContinuousQuery = null

    try {
       query =
        ds.map(i => (i, i * 1000))
          .toDF("id", "value")
          .write
          .format("parquet")
          .partitionBy("id")
          .option("checkpointLocation", checkpointDir)
          .startStream(outputDir)

      inputData.addData(1, 2, 3)
      failAfter(streamingTimeout) {
        query.processAllAvailable()
      }

      // TODO (tdas): Test partition column can be read or not
      val outputDf = sqlContext.read.parquet(outputDir)
      checkDataset(
        outputDf.as[Int],
        1000, 2000, 3000)

    } finally {
      if (query != null) {
        query.stop()
      }
    }
  }

  test("FileStreamSink - supported formats") {
    def testFormat(format: Option[String]): Unit = {
      val inputData = MemoryStream[Int]
      val ds = inputData.toDS()

      val outputDir = Utils.createTempDir(namePrefix = "stream.output").getCanonicalPath
      val checkpointDir = Utils.createTempDir(namePrefix = "stream.checkpoint").getCanonicalPath

      var query: ContinuousQuery = null

      try {
        val writer =
          ds.map(i => (i, i * 1000))
            .toDF("id", "value")
            .write
        if (format.nonEmpty) {
          writer.format(format.get)
        }
        query = writer
            .option("checkpointLocation", checkpointDir)
            .startStream(outputDir)
      } finally {
        if (query != null) {
          query.stop()
        }
      }
    }

    testFormat(None) // should not throw error as default format parquet when not specified
    testFormat(Some("parquet"))
    val e = intercept[UnsupportedOperationException] {
      testFormat(Some("text"))
    }
    Seq("text", "not support", "stream").foreach { s =>
      assert(e.getMessage.contains(s))
    }
  }

  private def checkFilesExist(dir: File, expectedFiles: Seq[String], msg: String): Unit = {
    import scala.collection.JavaConverters._
    val files =
      FileUtils.listFiles(dir, new RegexFileFilter("[^.]+"), DirectoryFileFilter.DIRECTORY)
        .asScala
        .map(_.getCanonicalPath)
        .toSet

    expectedFiles.foreach { f =>
      assert(files.contains(f),
        s"\n$msg\nexpected file:\n\t$f\nfound files:\n${files.mkString("\n\t")}")
    }
  }

}
