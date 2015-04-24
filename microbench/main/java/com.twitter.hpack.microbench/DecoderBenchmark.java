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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@State(Scope.Benchmark)
public class DecoderBenchmark extends AbstractMicrobenchmarkBase {

    public enum HeadersSize {
        SMALL, MEDIUM, LARGE, JUMBO
    }

    @Param ({"4096"})
    public int maxTableSize;

    @Param ({"8192"})
    public int maxHeaderSize;

    @Param({"true", "false"})
    public boolean sensitive;

    @Param
    public HeadersSize size;

    private byte[] input;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        switch (size) {
            case SMALL:
                input = getSerializedHeaders(Header.createHeaders(5, 20, 20), sensitive);
                break;
            case MEDIUM:
                input = getSerializedHeaders(Header.createHeaders(20, 40, 40), sensitive);
                break;
            case LARGE:
                input = getSerializedHeaders(Header.createHeaders(100, 100, 100), sensitive);
                break;
            case JUMBO:
                input = getSerializedHeaders(Header.createHeaders(300, 300, 300), sensitive);
                break;
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void decode(final Blackhole bh) throws IOException {
        Decoder decoder = new Decoder(maxHeaderSize, maxTableSize);
        decoder.decode(new ByteArrayInputStream(input), new HeaderListener() {
            @Override
            public void addHeader(byte[] name, byte[] value, boolean sensitive) {
                bh.consume(name);
                bh.consume(value);
                bh.consume(sensitive);
            }
        });
        decoder.endHeaderBlock();
    }

    private static byte[] getSerializedHeaders(List<Header> headers, boolean sensitive) throws IOException {
        Encoder encoder = new Encoder(4096);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1048576);
        for (int i = 0; i < headers.size(); ++i) {
            Header header = headers.get(i);
            encoder.encodeHeader(outputStream, header.name, header.value, sensitive);
        }
        return outputStream.toByteArray();
    }
}
