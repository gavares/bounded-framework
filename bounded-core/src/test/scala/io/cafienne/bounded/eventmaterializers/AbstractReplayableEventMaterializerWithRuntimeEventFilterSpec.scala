/*
 * Copyright (C) 2016-2020 Cafienne B.V. <https://www.cafienne.io/bounded>
 */

package io.cafienne.bounded.eventmaterializers

import java.time.{OffsetDateTime, ZonedDateTime}
import java.util.UUID

import akka.Done
import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.event.{Logging, LoggingAdapter}
import akka.persistence.query.Sequence
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import com.typesafe.scalalogging.Logger
import io.cafienne.bounded.aggregate.{DomainEvent, MetaData}
import io.cafienne.bounded._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration._

case class TestMetaData(
  timestamp: OffsetDateTime,
  userContext: Option[UserContext],
  causedByCommand: Option[UUID],
  buildInfo: BuildInfo,
  runTimeInfo: RuntimeInfo
) extends MetaData

case class TestedEvent(metaData: TestMetaData, text: String) extends DomainEvent {
  def id: String = "entityId"
}

class AbstractReplayableEventMaterializerWithRuntimeEventFilterSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll {

  //Setup required supporting classes
  implicit val timeout                = Timeout(10.seconds)
  implicit val system                 = ActorSystem("MaterializerTestSystem", SpecConfig.testConfig)
  implicit val logger: LoggingAdapter = Logging(system, getClass)
  implicit val defaultPatience        = PatienceConfig(timeout = Span(4, Seconds), interval = Span(100, Millis))
  implicit val buildInfo              = BuildInfo("spec", "1.0")
  implicit val runtimeInfo            = RuntimeInfo("current")

  val eventStreamListener = TestProbe()

  val currentRuntime = runtimeInfo
  val otherRuntime   = runtimeInfo.copy("previous")

  val currentBuild  = buildInfo
  val previousBuild = buildInfo.copy(version = "0.9")
  val futureBuild   = buildInfo.copy(version = "1.1")

  val currentMeta =
    TestMetaData(OffsetDateTime.parse("2018-01-01T17:43:00+01:00"), None, None, currentBuild, currentRuntime)

  val testSet = Seq(
    TestedEvent(currentMeta, "current-current"),
    TestedEvent(currentMeta.copy(buildInfo = previousBuild), "previous-current"),
    TestedEvent(currentMeta.copy(buildInfo = futureBuild), "future-current"),
    TestedEvent(currentMeta.copy(runTimeInfo = otherRuntime), "current-other"),
    TestedEvent(currentMeta.copy(buildInfo = previousBuild, runTimeInfo = otherRuntime), "previous-other"),
    TestedEvent(currentMeta.copy(buildInfo = futureBuild, runTimeInfo = otherRuntime), "future-other")
  )

  "The Event Materializer" must {

    "materialize all given events" in {
      val materializer = new TestMaterializer(DefaultCompatibility)

      val toBeRun = new EventMaterializers(List(materializer))
      whenReady(toBeRun.startUp(false)) { replayResult =>
        logger.debug("replayResult: {}", replayResult)
        assert(replayResult.head.offset == Some(Sequence(6L)))
      }
      logger.debug("DUMP all given events {}", materializer.storedEvents)
      assert(materializer.storedEvents.size == 6)
    }

    "materialize all events within the current runtime" in {
      val materializer = new TestMaterializer(Compatibility(RuntimeCompatibility.CURRENT))

      val toBeRun = new EventMaterializers(List(materializer))
      whenReady(toBeRun.startUp(false)) { replayResult =>
        logger.debug("replayResult: {}", replayResult)
        assert(replayResult.head.offset == Some(Sequence(3L)))
      }
      logger.debug("DUMP current runtime and all versions {}", materializer.storedEvents)
      assert(materializer.storedEvents.size == 3)
    }

  }

  private def populateEventStore(evt: Seq[DomainEvent]): Unit = {
    val storeEventsActor = system.actorOf(Props(classOf[CreateEventsInStoreActor], evt.head.id), "create-events-actor")

    val testProbe = TestProbe()
    testProbe watch storeEventsActor

    system.eventStream.subscribe(eventStreamListener.ref, classOf[EventProcessed])

    evt foreach { event =>
      testProbe.send(storeEventsActor, event)
      testProbe.expectMsgAllConformingOf(classOf[DomainEvent])
    }

    storeEventsActor ! PoisonPill
    val terminated = testProbe.expectTerminated(storeEventsActor)
    assert(terminated.existenceConfirmed)

  }

  override def beforeAll(): Unit = {
    populateEventStore(testSet)
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system, 30.seconds, verifySystemShutdown = true)
  }

  class TestMaterializer(compatible: Compatibility)(implicit runtimeInfo: RuntimeInfo)
      extends AbstractReplayableEventMaterializer(
        system,
        false,
        new RuntimeMaterializerEventFilter(runtimeInfo, compatible)
      ) {

    var storedEvents = Seq[DomainEvent]()

    override val logger: Logger = Logger(LoggerFactory.getLogger(TestMaterializer.this.getClass))

    /**
      * Tagname used to identify eventstream to listen to
      */
    override val tagName: String = "testar"

    /**
      * Mapping name of this listener
      */
    override val matMappingName: String = "testar"

    /**
      * Handle new incoming event
      *
      * @param evt event
      */
    override def handleEvent(evt: Any): Future[Done] = {
      logger.debug("TestMaterializer got event {} ", evt)
      evt match {
        case x: DomainEvent => storedEvents = storedEvents :+ x
        case other          => logger.warn("unkown event will not be stored {}", other)
      }
      Future.successful(Done)
    }

    override def handleReplayEvent(evt: Any): Future[Done] = handleEvent(evt)

    override def toString: String = s"TestMaterializer $tagName contains ${storedEvents.mkString(",")}"
  }

}
