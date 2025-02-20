/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.cluster.sharding

import org.apache.pekko
import pekko.actor.{ Actor, ActorLogging, ActorRef, Props }
import pekko.cluster.{ Cluster, MemberStatus }
import pekko.testkit.{ ImplicitSender, PekkoSpec, TestProbe }
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object RememberEntitiesBatchedUpdatesSpec {

  case class EntityEnvelope(id: Int, msg: Any)

  object EntityActor {
    case class Started(id: Int)
    case class Stopped(id: Int)
    def props(probe: ActorRef) = Props(new EntityActor(probe))
  }
  class EntityActor(probe: ActorRef) extends Actor with ActorLogging {
    import EntityActor._
    probe ! Started(self.path.name.toInt)
    override def receive: Receive = {
      case "stop" =>
        log.debug("Got stop message, stopping")
        context.stop(self)
      case "graceful-stop" =>
        log.debug("Got a graceful stop, requesting passivation")
        context.parent ! ShardRegion.Passivate("stop")
      case "start" =>
        log.debug("Got a start")
      case "ping" =>
    }

    override def postStop(): Unit = {
      probe ! Stopped(self.path.name.toInt)
    }
  }

  def config = ConfigFactory.parseString("""
      pekko.loglevel=DEBUG
      # pekko.loggers = ["org.apache.pekko.testkit.SilenceAllTestEventListener"]
      pekko.actor.provider = cluster
      pekko.remote.artery.canonical.port = 0
      pekko.remote.classic.netty.tcp.port = 0
      pekko.cluster.sharding.state-store-mode = ddata
      pekko.cluster.sharding.remember-entities = on
      # no leaks between test runs thank you
      pekko.cluster.sharding.distributed-data.durable.keys = []
      pekko.cluster.sharding.verbose-debug-logging = on
      pekko.cluster.sharding.fail-on-invalid-entity-state-transition = on
    """.stripMargin)
}
class RememberEntitiesBatchedUpdatesSpec
    extends PekkoSpec(RememberEntitiesBatchedUpdatesSpec.config)
    with AnyWordSpecLike
    with ImplicitSender {

  import RememberEntitiesBatchedUpdatesSpec._

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(id, payload) => (id.toString, payload)
    case _                           => throw new IllegalArgumentException()
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(_, _)       => "1" // single shard for all entities
    case ShardRegion.StartEntity(_) => "1"
    case _                          => throw new IllegalArgumentException()
  }

  override def atStartup(): Unit = {
    // Form a one node cluster
    val cluster = Cluster(system)
    cluster.join(cluster.selfAddress)
    awaitAssert(cluster.readView.members.count(_.status == MemberStatus.Up) should ===(1))
  }

  "Batching of starts and stops" must {

    "work" in {
      val probe = TestProbe()
      val sharding = ClusterSharding(system).start(
        "batching",
        EntityActor.props(probe.ref),
        ClusterShardingSettings(system),
        extractEntityId,
        extractShardId)

      // make sure that sharding is up and running
      sharding.tell(EntityEnvelope(0, "ping"), probe.ref)
      probe.expectMsg(EntityActor.Started(0))

      // start 20, should write first and batch the rest
      (1 to 20).foreach { i =>
        sharding ! EntityEnvelope(i, "start")
      }
      probe.receiveN(20)

      // start 20 more, and stop the previous ones that are already running,
      // should create a mixed batch of start + stops
      (21 to 40).foreach { i =>
        sharding ! EntityEnvelope(i, "start")
        sharding ! EntityEnvelope(i - 20, "graceful-stop")
      }
      probe.receiveN(40)
      // stop the last 20, should batch stops only
      (21 to 40).foreach { i =>
        sharding ! EntityEnvelope(i, "graceful-stop")
      }
      probe.receiveN(20)
    }

  }

}
