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

package org.apache.pekko.cluster

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import org.apache.pekko
import pekko.remote.testkit.MultiNodeConfig
import pekko.remote.transport.ThrottlerTransportAdapter.Direction
import pekko.testkit._

object ClusterAccrualFailureDetectorMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("pekko.cluster.failure-detector.threshold = 4"))
      .withFallback(MultiNodeClusterSpec.clusterConfig))

  testTransport(on = true)
}

class ClusterAccrualFailureDetectorMultiJvmNode1 extends ClusterAccrualFailureDetectorSpec
class ClusterAccrualFailureDetectorMultiJvmNode2 extends ClusterAccrualFailureDetectorSpec
class ClusterAccrualFailureDetectorMultiJvmNode3 extends ClusterAccrualFailureDetectorSpec

abstract class ClusterAccrualFailureDetectorSpec
    extends MultiNodeClusterSpec(ClusterAccrualFailureDetectorMultiJvmSpec) {

  import ClusterAccrualFailureDetectorMultiJvmSpec._

  muteMarkingAsUnreachable()

  "A heartbeat driven Failure Detector" must {

    "receive heartbeats so that all member nodes in the cluster are marked 'available'" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third)

      Thread.sleep(5.seconds.dilated.toMillis) // let them heartbeat
      cluster.failureDetector.isAvailable(first) should ===(true)
      cluster.failureDetector.isAvailable(second) should ===(true)
      cluster.failureDetector.isAvailable(third) should ===(true)

      enterBarrier("after-1")
    }

    "mark node as 'unavailable' when network partition and then back to 'available' when partition is healed" taggedAs
    LongRunningTest in {
      runOn(first) {
        testConductor.blackhole(first, second, Direction.Both).await
      }

      enterBarrier("broken")

      runOn(first) {
        // detect failure...
        awaitCond(!cluster.failureDetector.isAvailable(second), 15.seconds)
        // other connections still ok
        cluster.failureDetector.isAvailable(third) should ===(true)
      }

      runOn(second) {
        // detect failure...
        awaitCond(!cluster.failureDetector.isAvailable(first), 15.seconds)
        // other connections still ok
        cluster.failureDetector.isAvailable(third) should ===(true)
      }

      enterBarrier("partitioned")

      runOn(first) {
        testConductor.passThrough(first, second, Direction.Both).await
      }

      enterBarrier("repaired")

      runOn(first, third) {
        awaitCond(cluster.failureDetector.isAvailable(second), 15.seconds)
      }

      runOn(second) {
        awaitCond(cluster.failureDetector.isAvailable(first), 15.seconds)
      }

      enterBarrier("after-2")
    }

    "mark node as 'unavailable' if a node in the cluster is shut down (and its heartbeats stops)" taggedAs LongRunningTest in {
      runOn(first) {
        testConductor.exit(third, 0).await
      }

      enterBarrier("third-shutdown")

      runOn(first, second) {
        // remaining nodes should detect failure...
        awaitCond(!cluster.failureDetector.isAvailable(third), 15.seconds)
        // other connections still ok
        cluster.failureDetector.isAvailable(first) should ===(true)
        cluster.failureDetector.isAvailable(second) should ===(true)
      }

      enterBarrier("after-3")
    }
  }
}
