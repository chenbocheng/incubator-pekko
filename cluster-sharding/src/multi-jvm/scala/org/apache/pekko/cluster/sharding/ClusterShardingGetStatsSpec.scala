/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.cluster.sharding

import scala.concurrent.duration._

import org.apache.pekko
import pekko.actor._
import pekko.cluster.Cluster
import pekko.cluster.MemberStatus
import pekko.testkit.TestDuration
import pekko.testkit.TestProbe
import com.typesafe.config.ConfigFactory

object ClusterShardingGetStatsSpec {
  import MultiNodeClusterShardingSpec.PingPongActor

  val shardTypeName = "Ping"

  val numberOfShards = 3

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg @ PingPongActor.Ping(id) => (id.toString, msg)
  }
  val extractShardId: ShardRegion.ExtractShardId = {
    case PingPongActor.Ping(id) => (id % numberOfShards).toString
    case _                      => throw new IllegalArgumentException()
  }
}

object ClusterShardingGetStatsSpecConfig
    extends MultiNodeClusterShardingConfig(additionalConfig = """
        pekko.log-dead-letters-during-shutdown = off
        pekko.cluster.sharding.updating-state-timeout = 2s
        pekko.cluster.sharding.waiting-for-state-timeout = 2s
        """) {

  val controller = role("controller")
  val first = role("first")
  val second = role("second")
  val third = role("third")

  nodeConfig(first, second, third)(ConfigFactory.parseString("""pekko.cluster.roles=["shard"]"""))

}

class ClusterShardingGetStatsSpecMultiJvmNode1 extends ClusterShardingGetStatsSpec
class ClusterShardingGetStatsSpecMultiJvmNode2 extends ClusterShardingGetStatsSpec
class ClusterShardingGetStatsSpecMultiJvmNode3 extends ClusterShardingGetStatsSpec
class ClusterShardingGetStatsSpecMultiJvmNode4 extends ClusterShardingGetStatsSpec

abstract class ClusterShardingGetStatsSpec extends MultiNodeClusterShardingSpec(ClusterShardingGetStatsSpecConfig) {

  import ClusterShardingGetStatsSpec._
  import ClusterShardingGetStatsSpecConfig._
  import MultiNodeClusterShardingSpec.PingPongActor

  def startShard(): ActorRef = {
    startSharding(
      system,
      typeName = shardTypeName,
      entityProps = Props(new PingPongActor),
      settings = settings.withRole("shard"),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId)
  }

  lazy val region = ClusterSharding(system).shardRegion(shardTypeName)

  "Inspecting cluster sharding state" must {

    "join cluster" in {
      Seq(controller, first, second, third).foreach { node =>
        join(from = node, to = controller)
      }

      // make sure all nodes are up
      within(10.seconds) {
        awaitAssert {
          Cluster(system).state.members.count(_.status == MemberStatus.Up) should ===(4)
        }
      }

      runOn(controller) {
        startProxy(
          system,
          typeName = shardTypeName,
          role = Some("shard"),
          extractEntityId = extractEntityId,
          extractShardId = extractShardId)
      }
      runOn(first, second, third) {
        startShard()
      }

      enterBarrier("sharding started")
    }

    "return empty state when no sharded actors has started" in {

      within(10.seconds) {
        awaitAssert {
          val probe = TestProbe()
          region.tell(ShardRegion.GetClusterShardingStats(10.seconds.dilated), probe.ref)
          val shardStats = probe.expectMsgType[ShardRegion.ClusterShardingStats]
          shardStats.regions.size should ===(3)
          shardStats.regions.values.map(_.stats.size).sum should ===(0)
          shardStats.regions.keys.forall(_.hasGlobalScope) should ===(true)
          shardStats.regions.values.forall(_.failed.isEmpty) shouldBe true
        }
      }

      enterBarrier("empty sharding")
    }

    "trigger sharded actors" in {
      runOn(controller) {
        within(10.seconds) {
          awaitAssert {
            val pingProbe = TestProbe()
            // trigger starting of 2 entities on first and second node
            // but leave third node without entities
            List(1, 2, 4, 6).foreach(n => region.tell(PingPongActor.Ping(n), pingProbe.ref))
            pingProbe.receiveWhile(messages = 4) {
              case PingPongActor.Pong => ()
            }
          }
        }
      }
      enterBarrier("sharded actors started")
    }

    "get shard stats" in {
      within(10.seconds) {
        awaitAssert {
          val probe = TestProbe()
          val region = ClusterSharding(system).shardRegion(shardTypeName)
          region.tell(ShardRegion.GetClusterShardingStats(10.seconds.dilated), probe.ref)
          val regions = probe.expectMsgType[ShardRegion.ClusterShardingStats].regions
          regions.size shouldEqual 3
          regions.values.flatMap(_.stats.values).sum shouldEqual 4
          regions.values.forall(_.failed.isEmpty) shouldBe true
          regions.keys.forall(_.hasGlobalScope) shouldBe true
        }
      }
      enterBarrier("received shard stats")
    }

    "return stats after a node leaves" in {
      runOn(controller) {
        Cluster(system).leave(node(third).address)
      }

      runOn(controller, first, second) {
        within(30.seconds) {
          awaitAssert {
            Cluster(system).state.members.size should ===(3)
          }
        }
      }

      enterBarrier("third node removed")
      system.log.info("third node removed")

      runOn(controller) {
        within(10.seconds) {
          awaitAssert {
            val pingProbe = TestProbe()
            // make sure we have the 4 entities still alive across the fewer nodes
            List(1, 2, 4, 6).foreach(n => region.tell(PingPongActor.Ping(n), pingProbe.ref))
            pingProbe.receiveWhile(messages = 4) {
              case PingPongActor.Pong => ()
            }
          }
        }
      }

      enterBarrier("shards revived")

      runOn(controller) {
        within(20.seconds) {
          awaitAssert {
            val probe = TestProbe()
            region.tell(ShardRegion.GetClusterShardingStats(20.seconds.dilated), probe.ref)
            val regions = probe.expectMsgType[ShardRegion.ClusterShardingStats].regions
            regions.size === 2
            regions.values.flatMap(_.stats.values).sum should ===(4)
          }
        }
      }

      enterBarrier("done")
    }
  }
}
