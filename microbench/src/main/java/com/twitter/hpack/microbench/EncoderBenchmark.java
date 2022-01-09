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

import com.twitter.hpack.Encoder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

public class EncoderBenchmark extends AbstractMicrobenchmarkBase {

    @Param
    public HeadersSize size;

    @Param ({"4096"})
    public int maxTableSize;

    @Param({"true", "false"})
    public boolean sensitive;

    @Param({"true", "false"})
    public boolean duplicates;

    @Param({"true", "false"})
    public boolean limitToAscii;

    private List<Header> headers;
    private ByteArrayOutputStream outputStream;

    @Setup(Level.Trial)
    public void setup() {
        headers = headers(size, limitToAscii);
        outputStream = size.newOutputStream();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void encode(final Blackhole bh) throws IOException {
        final Encoder encoder = new Encoder(maxTableSize);
        outputStream.reset();
        if (duplicates) {
            // If duplicates is set, re-add the same header each time.
            final Header header = headers.get(0);
            for (int i = 0; i < headers.size(); ++i) {
                encoder.encodeHeader(outputStream, header.name, header.value, sensitive);
            }
        } else {
            for (int i = 0; i < headers.size(); ++i) {
                final Header header = headers.get(i);
                encoder.encodeHeader(outputStream, header.name, header.value, sensitive);
            }
        }
        bh.consume(outputStream);
    }
}
