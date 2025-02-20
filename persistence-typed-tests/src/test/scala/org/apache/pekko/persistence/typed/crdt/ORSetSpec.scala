/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.typed.crdt

import org.apache.pekko
import pekko.actor.typed.{ ActorRef, Behavior }
import pekko.persistence.testkit.query.scaladsl.PersistenceTestKitReadJournal
import pekko.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplicatedEventSourcing }
import pekko.persistence.typed.{ ReplicaId, ReplicationBaseSpec }
import ORSetSpec.ORSetEntity._
import pekko.persistence.typed.ReplicationBaseSpec.{ R1, R2 }
import pekko.persistence.typed.ReplicationId
import pekko.persistence.typed.crdt.ORSetSpec.ORSetEntity

import scala.util.Random

object ORSetSpec {

  import ReplicationBaseSpec._

  object ORSetEntity {
    sealed trait Command
    final case class Get(replyTo: ActorRef[Set[String]]) extends Command
    final case class Add(elem: String) extends Command
    final case class AddAll(elems: Set[String]) extends Command
    final case class Remove(elem: String) extends Command

    def apply(entityId: String, replica: ReplicaId): Behavior[ORSetEntity.Command] = {

      ReplicatedEventSourcing.commonJournalConfig(
        ReplicationId("ORSetSpec", entityId, replica),
        AllReplicas,
        PersistenceTestKitReadJournal.Identifier) { replicationContext =>
        EventSourcedBehavior[Command, ORSet.DeltaOp, ORSet[String]](
          replicationContext.persistenceId,
          ORSet(replica),
          (state, command) =>
            command match {
              case Add(elem) =>
                Effect.persist(state + elem)
              case AddAll(elems) =>
                Effect.persist(state.addAll(elems.toSet))
              case Remove(elem) =>
                Effect.persist(state - elem)
              case Get(replyTo) =>
                Effect.none.thenRun(state => replyTo ! state.elements)

            },
          (state, operation) => state.applyOperation(operation))
      }
    }
  }

}

class ORSetSpec extends ReplicationBaseSpec {

  class Setup {
    val entityId = nextEntityId
    val r1 = spawn(ORSetEntity.apply(entityId, R1))
    val r2 = spawn(ORSetEntity.apply(entityId, R2))
    val r1GetProbe = createTestProbe[Set[String]]()
    val r2GetProbe = createTestProbe[Set[String]]()

    def assertForAllReplicas(state: Set[String]): Unit = {
      eventually {
        r1 ! Get(r1GetProbe.ref)
        r1GetProbe.expectMessage(state)
        r2 ! Get(r2GetProbe.ref)
        r2GetProbe.expectMessage(state)
      }
    }
  }

  def randomDelay(): Unit = {
    // exercise different timing scenarios
    Thread.sleep(Random.nextInt(200).toLong)
  }

  "ORSet Replicated Entity" should {

    "support concurrent updates" in new Setup {
      r1 ! Add("a1")
      r2 ! Add("b1")
      assertForAllReplicas(Set("a1", "b1"))
      r2 ! Remove("b1")
      assertForAllReplicas(Set("a1"))
      r2 ! Add("b1")
      for (n <- 2 to 10) {
        r1 ! Add(s"a$n")
        if (n % 3 == 0)
          randomDelay()
        r2 ! Add(s"b$n")
      }
      r1 ! AddAll((11 to 13).map(n => s"a$n").toSet)
      r2 ! AddAll((11 to 13).map(n => s"b$n").toSet)
      val expected = (1 to 13).flatMap(n => List(s"a$n", s"b$n")).toSet
      assertForAllReplicas(expected)
    }
  }
}
