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

package org.apache.pekko.persistence.typed.scaladsl

import org.apache.pekko
import pekko.actor.testkit.typed.TestKitSettings
import pekko.actor.testkit.typed.scaladsl._
import pekko.actor.typed.ActorRef
import pekko.actor.typed.Behavior
import pekko.persistence.typed.PersistenceId
import pekko.persistence.typed.RecoveryCompleted
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object NullEmptyStateSpec {

  private val conf = ConfigFactory.parseString(s"""
      pekko.persistence.journal.plugin = "pekko.persistence.journal.inmem"
      pekko.persistence.journal.inmem.test-serialization = on
    """)
}

class NullEmptyStateSpec
    extends ScalaTestWithActorTestKit(NullEmptyStateSpec.conf)
    with AnyWordSpecLike
    with LogCapturing {

  implicit val testSettings: TestKitSettings = TestKitSettings(system)

  def nullState(persistenceId: PersistenceId, probe: ActorRef[String]): Behavior[String] =
    EventSourcedBehavior[String, String, String](
      persistenceId,
      emptyState = null,
      commandHandler = (_, command) => {
        if (command == "stop")
          Effect.stop()
        else
          Effect.persist(command)
      },
      eventHandler = (state, event) => {
        probe.tell("eventHandler:" + state + ":" + event)
        if (state == null) event else state + event
      }).receiveSignal {
      case (state, RecoveryCompleted) =>
        probe.tell("onRecoveryCompleted:" + state)
    }

  "A typed persistent actor with null empty state" must {
    "persist events and update state" in {
      val probe = TestProbe[String]()
      val b = nullState(PersistenceId.ofUniqueId("a"), probe.ref)
      val ref1 = spawn(b)
      probe.expectMessage("onRecoveryCompleted:null")
      ref1 ! "one"
      probe.expectMessage("eventHandler:null:one")
      ref1 ! "two"
      probe.expectMessage("eventHandler:one:two")

      ref1 ! "stop"
      // wait till ref1 stops
      probe.expectTerminated(ref1)

      val ref2 = testKit.spawn(b)
      // eventHandler from reply
      probe.expectMessage("eventHandler:null:one")
      probe.expectMessage("eventHandler:one:two")
      probe.expectMessage("onRecoveryCompleted:onetwo")
      ref2 ! "three"
      probe.expectMessage("eventHandler:onetwo:three")
    }

  }
}
