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

package org.apache.pekko.testkit.metrics

import java.util.concurrent.atomic.LongAdder

import com.codahale.metrics.Gauge

/**
 * Gauge which exposes the Arithmetic Mean of values given to it.
 *
 * Can be used to expose average of a series of values to `com.codahale.metrics.ScheduledReporter`.
 */
class AveragingGauge extends Gauge[Double] {

  private val sum = new LongAdder
  private val count = new LongAdder

  def add(n: Long): Unit = {
    count.increment()
    sum.add(n)
  }

  def add(ns: Seq[Long]): Unit = {
    // takes a mutable Seq on order to allow use with Array's
    count.add(ns.length)
    sum.add(ns.sum)
  }

  override def getValue: Double = sum.sum().toDouble / count.sum()
}
