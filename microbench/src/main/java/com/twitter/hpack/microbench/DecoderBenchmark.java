/*
 * Copyright 2015 Twitter, Inc.
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
package com.twitter.hpack.microbench;

import com.twitter.hpack.Decoder;
import com.twitter.hpack.Encoder;
import com.twitter.hpack.ExtendedHeaderListener;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

public class DecoderBenchmark extends AbstractMicrobenchmarkBase {

    static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

    static final Blackhole bh = new Blackhole();

    static final ExtendedHeaderListener RECEIVE_HEADERS_AND_PARSE_AS_STRING =
        new ExtendedHeaderListener() {
      @Override
      public Object addHeader(byte[] name, String nameString, byte[] value, Object valueAnnotation,
          boolean sensitive) {
        bh.consume(new String(name, ISO_8859_1));
        bh.consume(new String(value, ISO_8859_1));
        bh.consume(sensitive);
        return null;
      }
    };

    static final ExtendedHeaderListener RECEIVE_HEADERS_AND_ANNOTATE =
        new ExtendedHeaderListener() {
      @Override
      public Object addHeader(byte[] name, String nameString, byte[] value, Object valueAnnotation,
                              boolean sensitive) {
        bh.consume(nameString);
        if (valueAnnotation == null) {
          valueAnnotation = true;
        }
        bh.consume(valueAnnotation);
        bh.consume(sensitive);
        return valueAnnotation;
      }
    };

    @Param
    public HeadersSize size = HeadersSize.SMALL;

    @Param ({"4096"})
    public int maxTableSize = 4096;

    @Param ({"8192"})
    public int maxHeaderSize = 8192;

    @Param({"true", "false"})
    public boolean sensitive;

    @Param({"true", "false"})
    public boolean limitToAscii;

    private byte[][] input;

    private Decoder commonDecoder;
    private ByteArrayInputStream repeatedInput;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        input = getSerializedHeaders(headers(size, limitToAscii), sensitive);

        // Initialize the common decoder with the initial input as we're not interested in
        // measuring the first decode pass.
        commonDecoder = new Decoder(maxHeaderSize, maxTableSize);
        commonDecoder.decode(new ByteArrayInputStream(input[0]),
            new ExtendedHeaderListener() {
          @Override
          public Object addHeader(byte[] name, String nameString, byte[] value,
                                  Object valueAnnotation, boolean sensitive) {
            return null;
          }
        });
        repeatedInput = new ByteArrayInputStream(input[1]);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void decode(final Blackhole bh) throws IOException {
        Decoder decoder = new Decoder(maxHeaderSize, maxTableSize);
        decoder.decode(new ByteArrayInputStream(input[0]), new ExtendedHeaderListener() {
            @Override
            public Object addHeader(byte[] name, String nameString, byte[] value, Object annotation,
                                    boolean sensitive) {
                bh.consume(sensitive);
                return null;
            }
        });
        decoder.endHeaderBlock();
    }

    // Compare with decodeAndAnnotateNameAndValueAsString to see value of caching the
    // the parsed product of a header.
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void decodeAndParseNameAndValueAsString() throws IOException {
      repeatedInput.reset();
      commonDecoder.decode(repeatedInput, RECEIVE_HEADERS_AND_PARSE_AS_STRING);
      commonDecoder.endHeaderBlock();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void decodeAndAnnotateNameAndValueAsString() throws IOException {
      repeatedInput.reset();
      commonDecoder.decode(repeatedInput, RECEIVE_HEADERS_AND_ANNOTATE);
      commonDecoder.endHeaderBlock();
    }

    private byte[][] getSerializedHeaders(List<Header> headers, boolean sensitive)
        throws IOException {
        Encoder encoder = new Encoder(4096);

        ByteArrayOutputStream outputStream = size.newOutputStream();
        for (int i = 0; i < headers.size(); ++i) {
            Header header = headers.get(i);
            encoder.encodeHeader(outputStream, header.name, header.value, sensitive);
        }

        ByteArrayOutputStream outputStream2 = size.newOutputStream();
        for (int i = 0; i < headers.size(); ++i) {
          Header header = headers.get(i);
          encoder.encodeHeader(outputStream2, header.name, header.value, sensitive);
        }
        return new byte[][]{outputStream.toByteArray(), outputStream2.toByteArray()};
    }

    public static void main(String[] argv) throws Exception {
      DecoderBenchmark decoderBenchmark = new DecoderBenchmark();
      decoderBenchmark.setup();
      for (int i= 0; i < 1000000; i++) {
        decoderBenchmark.decodeAndAnnotateNameAndValueAsString();
      }
    }
}
