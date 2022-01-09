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
import com.twitter.hpack.HeaderListener;
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
import java.util.List;

public class DecoderBenchmark extends AbstractMicrobenchmarkBase {

    @Param
    public HeadersSize size;

    @Param ({"4096"})
    public int maxTableSize;

    @Param ({"8192"})
    public int maxHeaderSize;

    @Param({"true", "false"})
    public boolean sensitive;

    @Param({"true", "false"})
    public boolean limitToAscii;

    private byte[] input;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        input = getSerializedHeaders(headers(size, limitToAscii), sensitive);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void decode(final Blackhole bh) throws IOException {
        final Decoder decoder = new Decoder(maxHeaderSize, maxTableSize);
        decoder.decode(new ByteArrayInputStream(input), new HeaderListener() {
            @Override
            public void addHeader(final byte[] name, final byte[] value, final boolean sensitive) {
                bh.consume(sensitive);
            }
        });
        decoder.endHeaderBlock();
    }

    private byte[] getSerializedHeaders(final List<Header> headers, final boolean sensitive) throws IOException {
        final Encoder encoder = new Encoder(4096);

        final ByteArrayOutputStream outputStream = size.newOutputStream();
        for (int i = 0; i < headers.size(); ++i) {
            final Header header = headers.get(i);
            encoder.encodeHeader(outputStream, header.name, header.value, sensitive);
        }
        return outputStream.toByteArray();
    }
}
