/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.remote.classic.transport

import scala.concurrent.Await
import scala.concurrent.duration._

import scala.annotation.nowarn
import com.typesafe.config.{ Config, ConfigFactory }

import org.apache.pekko
import pekko.actor._
import pekko.remote.{ EndpointException, RemoteActorRefProvider }
import pekko.remote.classic.transport.ThrottlerTransportAdapterSpec._
import pekko.remote.transport.{ TestTransport, ThrottlerTransportAdapter }
import pekko.remote.transport.ThrottlerTransportAdapter._
import pekko.testkit.{ DefaultTimeout, EventFilter, ImplicitSender, PekkoSpec, TestEvent, TimingTest }

object ThrottlerTransportAdapterSpec {
  val configA: Config =
    ConfigFactory.parseString("""
    pekko {
      actor.provider = remote

      remote.artery.enabled = off
      remote.classic.netty.tcp.hostname = "localhost"
      remote.log-remote-lifecycle-events = off
      remote.retry-gate-closed-for = 1 s
      remote.classic.transport-failure-detector.heartbeat-interval = 1 s
      remote.classic.transport-failure-detector.acceptable-heartbeat-pause = 3 s

      remote.classic.netty.tcp.applied-adapters = ["trttl"]
      remote.classic.netty.tcp.port = 0
    }
    # test is using Java serialization and not priority to rewrite
    pekko.actor.allow-java-serialization = on
    pekko.actor.warn-about-java-serializer-usage = off
    """)

  class Echo extends Actor {
    override def receive = {
      case "ping" => sender() ! "pong"
      case x      => sender() ! x
    }
  }

  val PingPacketSize = 148
  val MessageCount = 30
  val BytesPerSecond = 500
  val TotalTime: Long = (MessageCount * PingPacketSize) / BytesPerSecond

  class ThrottlingTester(remote: ActorRef, controller: ActorRef) extends Actor {
    var messageCount = MessageCount
    var received = 0
    var startTime = 0L

    override def receive = {
      case "start" =>
        self ! "sendNext"
        startTime = System.nanoTime()
      case "sendNext" =>
        if (messageCount > 0) {
          remote ! "ping"
          self ! "sendNext"
          messageCount -= 1
        }
      case "pong" =>
        received += 1
        if (received >= MessageCount) controller ! (System.nanoTime() - startTime)
    }
  }

  final case class Lost(msg: String)
}

@nowarn("msg=deprecated")
class ThrottlerTransportAdapterSpec extends PekkoSpec(configA) with ImplicitSender with DefaultTimeout {

  val systemB = ActorSystem("systemB", system.settings.config)
  val remote = systemB.actorOf(Props[Echo](), "echo")

  val rootB = RootActorPath(systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress)
  val here = {
    system.actorSelection(rootB / "user" / "echo") ! Identify(None)
    expectMsgType[ActorIdentity].ref.get
  }

  def throttle(direction: Direction, mode: ThrottleMode): Boolean = {
    val rootBAddress = Address("pekko", "systemB", "localhost", rootB.address.port.get)
    val transport = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport
    Await.result(transport.managementCommand(SetThrottle(rootBAddress, direction, mode)), 3.seconds)
  }

  def disassociate(): Boolean = {
    val rootBAddress = Address("pekko", "systemB", "localhost", rootB.address.port.get)
    val transport = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport
    Await.result(transport.managementCommand(ForceDisassociate(rootBAddress)), 3.seconds)
  }

  "ThrottlerTransportAdapter" must {
    "maintain average message rate" taggedAs TimingTest in {
      throttle(Direction.Send, TokenBucket(200, 500, 0, 0)) should ===(true)
      system.actorOf(Props(classOf[ThrottlingTester], here, self)) ! "start"

      val time = NANOSECONDS.toSeconds(expectMsgType[Long]((TotalTime + 3).seconds))
      log.warning("Total time of transmission: " + time)
      time should be > (TotalTime - 3)
      throttle(Direction.Send, Unthrottled) should ===(true)
    }

    "survive blackholing" taggedAs TimingTest in {
      here ! Lost("Blackhole 1")
      expectMsg(Lost("Blackhole 1"))

      muteDeadLetters(classOf[Lost])(system)
      muteDeadLetters(classOf[Lost])(systemB)

      throttle(Direction.Both, Blackhole) should ===(true)

      here ! Lost("Blackhole 2")
      expectNoMessage(1.seconds)
      disassociate() should ===(true)
      expectNoMessage(1.seconds)

      throttle(Direction.Both, Unthrottled) should ===(true)

      // after we remove the Blackhole we can't be certain of the state
      // of the connection, repeat until success
      here ! Lost("Blackhole 3")
      awaitCond({
          if (receiveOne(Duration.Zero) == Lost("Blackhole 3"))
            true
          else {
            here ! Lost("Blackhole 3")
            false
          }
        }, 15.seconds)

      here ! "Cleanup"
      fishForMessage(5.seconds) {
        case "Cleanup"           => true
        case Lost("Blackhole 3") => false
      }
    }

  }

  override def beforeTermination(): Unit = {
    system.eventStream.publish(
      TestEvent.Mute(
        EventFilter.warning(source = s"pekko://AkkaProtocolStressTest/user/$$a", start = "received dead letter"),
        EventFilter.warning(pattern = "received dead letter.*(InboundPayload|Disassociate)")))
    systemB.eventStream.publish(
      TestEvent.Mute(
        EventFilter[EndpointException](),
        EventFilter.error(start = "AssociationError"),
        EventFilter.warning(pattern = "received dead letter.*(InboundPayload|Disassociate)")))
  }

  override def afterTermination(): Unit = shutdown(systemB)
}

@nowarn("msg=deprecated")
class ThrottlerTransportAdapterGenericSpec extends GenericTransportSpec(withAkkaProtocol = true) {

  def transportName = "ThrottlerTransportAdapter"
  def schemeIdentifier = "pekko.trttl"
  def freshTransport(testTransport: TestTransport) =
    new ThrottlerTransportAdapter(testTransport, system.asInstanceOf[ExtendedActorSystem])

}
