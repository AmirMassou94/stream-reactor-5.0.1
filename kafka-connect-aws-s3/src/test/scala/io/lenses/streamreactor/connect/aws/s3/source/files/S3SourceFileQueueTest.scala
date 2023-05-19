/*
 * Copyright 2017-2023 Lenses.io Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lenses.streamreactor.connect.aws.s3.source.files

import cats.implicits.catsSyntaxEitherId
import io.lenses.streamreactor.connect.aws.s3.model.location.RemoteS3PathLocation
import io.lenses.streamreactor.connect.aws.s3.model.location.RemoteS3RootLocation
import io.lenses.streamreactor.connect.aws.s3.storage.StorageInterface
import org.mockito.InOrder
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class S3SourceFileQueueTest extends AnyFlatSpec with Matchers with MockitoSugar {

  private implicit val storageInterface: StorageInterface = mock[StorageInterface]

  private val rootLocation = RemoteS3RootLocation("bucket:path")
  private val files        = (0 to 3).map(file => rootLocation.withPath(file.toString + ".json")).toList

  "list" should "cache a batch of results from the beginning" in {

    val (order: InOrder, sourceFileQueue: S3SourceFileQueue) = setUpListTest

    // file 0 = 0.json
    sourceFileQueue.next() should be(Right(Some(files(0).atLine(-1))))
    order.verify(sourceFileQueue).listBatch(rootLocation, None, 2)
    sourceFileQueue.markFileComplete(files(0))

    // file 1 = 1.json
    sourceFileQueue.next() should be(Right(Some(files(1).atLine(-1))))
    sourceFileQueue.markFileComplete(files(1))

    // file 2 = 2.json
    sourceFileQueue.next() should be(Right(Some(files(2).atLine(-1))))
    order.verify(sourceFileQueue).listBatch(rootLocation, Some(files(1)), 2)
    sourceFileQueue.markFileComplete(files(2))

    // file 3 = 3.json
    sourceFileQueue.next() should be(Right(Some(files(3).atLine(-1))))
    sourceFileQueue.markFileComplete(files(3))

    // No more files
    sourceFileQueue.next() should be(Right(None))
    order.verify(sourceFileQueue).listBatch(rootLocation, Some(files(3)), 2)

    // Try again, but still no more files
    sourceFileQueue.next() should be(Right(None))
    order.verify(sourceFileQueue).listBatch(rootLocation, Some(files(3)), 2)

  }

  private def setUpListTest = {

    val sourceFileQueue = spy(new S3SourceFileQueue(rootLocation, 2))
    doReturn(files.slice(0, 2).asRight).when(sourceFileQueue).listBatch(rootLocation, None, 2)
    doReturn(files.slice(2, 4).asRight).when(sourceFileQueue).listBatch(rootLocation, Some(files(1)), 2)
    doReturn(files.slice(3, 4).asRight).when(sourceFileQueue).listBatch(rootLocation, Some(files(2)), 2)
    doReturn(List.empty[RemoteS3PathLocation].asRight).when(sourceFileQueue).listBatch(rootLocation, Some(files(3)), 2)

    val order = inOrder(sourceFileQueue)

    (order, sourceFileQueue)
  }

  "list" should "process the init file before reading additional files" in {

    val (order: InOrder, sourceFileQueue: S3SourceFileQueue) = setUpListTest

    sourceFileQueue.init(files(2).atLine(1000))

    // file 2 = 2.json
    sourceFileQueue.next() should be(Right(Some(files(2).atLine(1000))))
    sourceFileQueue.markFileComplete(files(2))

    // file 3 = 3.json
    sourceFileQueue.next() should be(Right(Some(files(3).atLine(-1))))
    order.verify(sourceFileQueue).listBatch(rootLocation, Some(files(2)), 2)
    sourceFileQueue.markFileComplete(files(3))

    // No more files
    sourceFileQueue.next() should be(Right(None))
    order.verify(sourceFileQueue).listBatch(rootLocation, Some(files(3)), 2)
  }

  "markFileComplete" should "return error when no files in list" in {

    val (_: InOrder, sourceFileQueue: S3SourceFileQueue) = setUpListTest

    sourceFileQueue.markFileComplete(files(2)) should be(Left("No files in queue to mark as complete"))
  }

  "markFileComplete" should "return error when file is not next file" in {

    val (_: InOrder, sourceFileQueue: S3SourceFileQueue) = setUpListTest

    sourceFileQueue.init(files(1).atLine(100))

    sourceFileQueue.markFileComplete(files(2)) should be(
      Left("File (bucket:2.json) does not match that at head of the queue, which is (bucket:1.json)"),
    )

  }

  "listBatch" should "return first result when no TopicPartitionOffset has been provided" in {

    when(storageInterface.list(rootLocation, None, 10)).thenReturn(
      List("path/myTopic/0/100.json", "path/myTopic/0/200.json", "path/myTopic/0/300.json")
        .asRight,
    )

    new S3SourceFileQueue(rootLocation, 1000).listBatch(rootLocation, None, 10) should be(
      Right(
        List(
          rootLocation.withPath("path/myTopic/0/100.json"),
          rootLocation.withPath("path/myTopic/0/200.json"),
          rootLocation.withPath("path/myTopic/0/300.json"),
        ),
      ),
    )
  }

  "listBatch" should "return empty when no results are found" in {

    when(storageInterface.list(rootLocation, None, 10)).thenReturn(
      List.empty.asRight,
    )

    new S3SourceFileQueue(rootLocation, 1000).listBatch(rootLocation, None, 10) should be(
      Right(List()),
    )
  }

  "listBatch" should "take all the files regardless of extension" in {
    val actual = List(
      "path/myTopic/0/100.json",
      "path/myTopic/0/200.xls",
      "path/myTopic/0/300.doc",
      "path/myTopic/0/400.csv",
      "path/myTopic/0/500",
    )
    when(storageInterface.list(rootLocation, None, 10)).thenReturn(actual.asRight)

    new S3SourceFileQueue(rootLocation, 1000).listBatch(rootLocation, None, 10) shouldBe actual.map(
      rootLocation.withPath,
    ).asRight
  }

  "listBatch" should "return throwable when storageInterface errors" in {
    val exception = new IllegalStateException("BadThingsHappened")

    when(storageInterface.list(rootLocation, None, 10)).thenReturn(
      exception.asLeft,
    )

    new S3SourceFileQueue(rootLocation, 1000).listBatch(rootLocation, None, 10) should be(
      Left(exception),
    )
  }

}
