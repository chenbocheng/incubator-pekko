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

package jdocs.stream.operators.converters;

import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.stream.IOResult;
import org.apache.pekko.stream.javadsl.Flow;
import org.apache.pekko.stream.javadsl.Keep;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.stream.javadsl.StreamConverters;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.apache.pekko.util.ByteString;
import jdocs.AbstractJavaTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.apache.pekko.util.ByteString.emptyByteString;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ToFromJavaIOStreams extends AbstractJavaTest {

  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("ToFromJavaIOStreams");
  }

  @AfterClass
  public static void tearDown() {
    TestKit.shutdownActorSystem(system);
    system = null;
  }

  @Test
  public void demonstrateFromJavaIOStreams()
      throws InterruptedException, ExecutionException, TimeoutException {
    Charset charset = Charset.defaultCharset();
    byte[] bytes = "Some random input".getBytes(charset);
    Flow<ByteString, ByteString, NotUsed> toUpperCase =
        Flow.<ByteString>create()
            .map(
                bs -> {
                  String str = bs.decodeString(charset).toUpperCase();
                  return ByteString.fromString(str, charset);
                });

    // #tofromJavaIOStream
    java.io.InputStream inputStream = new ByteArrayInputStream(bytes);
    Source<ByteString, CompletionStage<IOResult>> source =
        StreamConverters.fromInputStream(() -> inputStream);

    // Given a ByteString produces a ByteString with the upperCase representation
    // Removed from the sample for brevity.
    // Flow<ByteString, ByteString, NotUsed> toUpperCase = ...

    java.io.OutputStream outputStream = new ByteArrayOutputStream();
    Sink<ByteString, CompletionStage<IOResult>> sink =
        StreamConverters.fromOutputStream(() -> outputStream);

    CompletionStage<IOResult> ioResultCompletionStage =
        source.via(toUpperCase).runWith(sink, system);
    // When the ioResultCompletionStage completes, the byte array backing the outputStream
    // will contain the uppercase representation of the bytes read from the inputStream.
    // #tofromJavaIOStream
    ioResultCompletionStage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(
        "SOME RANDOM INPUT",
        new String(((ByteArrayOutputStream) outputStream).toByteArray(), charset));
  }

  @Test
  public void demonstrateAsJavaInputStream() throws Exception {

    // #asJavaInputStream
    Charset charset = Charset.defaultCharset();
    Flow<ByteString, ByteString, NotUsed> toUpperCase =
        Flow.<ByteString>create()
            .map(
                bs -> {
                  String str = bs.decodeString(charset).toUpperCase();
                  return ByteString.fromString(str, charset);
                });

    final Sink<ByteString, InputStream> sink = StreamConverters.asInputStream();
    final InputStream stream =
        Source.single(ByteString.fromString("Some random input"))
            .via(toUpperCase)
            .runWith(sink, system);

    // #asJavaInputStream
    byte[] a = new byte[17];
    stream.read(a);
    assertArrayEquals("SOME RANDOM INPUT".getBytes(), a);
  }

  @Test
  public void demonstrateAsJavaOutputStream() throws Exception {

    // #asJavaOutputStream
    final Source<ByteString, OutputStream> source = StreamConverters.asOutputStream();
    final Sink<ByteString, CompletionStage<ByteString>> sink =
        Sink.fold(emptyByteString(), (ByteString arg1, ByteString arg2) -> arg1.concat(arg2));

    final Pair<OutputStream, CompletionStage<ByteString>> output =
        source.toMat(sink, Keep.both()).run(system);

    // #asJavaOutputStream
    byte[] bytesArray = new byte[3];
    output.first().write(bytesArray);
    output.first().close();

    final byte[] expected =
        output.second().toCompletableFuture().get(5, TimeUnit.SECONDS).toArray();

    assertArrayEquals(expected, bytesArray);
  }
}
