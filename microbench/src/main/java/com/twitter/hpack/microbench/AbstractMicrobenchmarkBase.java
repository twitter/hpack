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

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.junit.Test;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Base class for all JMH benchmarks.
 */
@Warmup(iterations = AbstractMicrobenchmarkBase.DEFAULT_WARMUP_ITERATIONS)
@Measurement(iterations = AbstractMicrobenchmarkBase.DEFAULT_MEASURE_ITERATIONS)
@Fork(value = AbstractMicrobenchmarkBase.DEFAULT_FORKS)
@State(Scope.Benchmark)
public abstract class AbstractMicrobenchmarkBase {
    private static final Logger logger = Logger.getLogger(AbstractMicrobenchmarkBase.class.getName());

    protected static final int DEFAULT_WARMUP_ITERATIONS = 5;
    protected static final int DEFAULT_MEASURE_ITERATIONS = 5;
    protected static final int DEFAULT_FORKS = 1;
    protected static final String[] JVM_ARGS = {
            "-server", "-dsa", "-da", "-XX:+AggressiveOpts", "-XX:+UseBiasedLocking",
            "-XX:+UseFastAccessorMethods", "-XX:+OptimizeStringConcat",
            "-XX:+HeapDumpOnOutOfMemoryError"};

    /**
     * Internal key used to index a particular set of headers in the map.
     */
    private static class HeadersKey {
        final HeadersSize size;
        final boolean limitToAscii;

        public HeadersKey(final HeadersSize size, final boolean limitToAscii) {
            this.size = size;
            this.limitToAscii = limitToAscii;
        }

        List<Header> newHeaders() {
            return size.newHeaders(limitToAscii);
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            final HeadersKey that = (HeadersKey) o;

            if (limitToAscii != that.limitToAscii) return false;
            return size == that.size;

        }

        @Override
        public int hashCode() {
            int result = size.hashCode();
            result = 31 * result + (limitToAscii ? 1 : 0);
            return result;
        }
    }

    private static final Map<HeadersKey, List<Header>> headersMap;
    static {
        final HeadersSize[] sizes = HeadersSize.values();
        headersMap = new HashMap<HeadersKey, List<Header>>(sizes.length * 2);
        for (final HeadersSize size : sizes) {
            HeadersKey key = new HeadersKey(size, true);
            headersMap.put(key, key.newHeaders());

            key = new HeadersKey(size, false);
            headersMap.put(key, key.newHeaders());
        }
    }

    /**
     * Gets headers for the given size and whether the key/values should be limited to ASCII.
     */
    protected static List<Header> headers(final HeadersSize size, final boolean limitToAscii) {
        return headersMap.get(new HeadersKey(size, limitToAscii));
    }

    protected ChainedOptionsBuilder newOptionsBuilder() throws Exception {
        final String className = getClass().getSimpleName();

        final ChainedOptionsBuilder runnerOptions = new OptionsBuilder()
                .include(".*" + className + ".*")
                .jvmArgs(jvmArgs());

        if (getWarmupIterations() > 0) {
            runnerOptions.warmupIterations(getWarmupIterations());
        }

        if (getMeasureIterations() > 0) {
            runnerOptions.measurementIterations(getMeasureIterations());
        }

        if (getForks() > 0) {
            runnerOptions.forks(getForks());
        }

        if (getReportDir() != null) {
            final String filePath = getReportDir() + className + ".json";
            final File file = new File(filePath);
            if (file.exists()) {
                file.delete();
            } else {
                file.getParentFile().mkdirs();
                file.createNewFile();
            }

            runnerOptions.resultFormat(ResultFormatType.JSON);
            runnerOptions.result(filePath);
        }

        return runnerOptions;
    }

    protected String[] jvmArgs() {
        return JVM_ARGS;
    }

    @Test
    public void run() throws Exception {
        new Runner(newOptionsBuilder().build()).run();
    }

    private int getWarmupIterations() {
        return getIntProperty("warmupIterations", -1);
    }

    private int getMeasureIterations() {
        return getIntProperty("measureIterations", -1);
    }

    private int getForks() {
        return getIntProperty("forks", -1);
    }

    private String getReportDir() {
        return System.getProperty("perfReportDir");
    }

    private static int getIntProperty(final String key, final int def) {
        final String value = System.getProperty(key);
        if (value == null) {
            return def;
        }

        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            logger.warning(
                    "Unable to parse the integer system property '" + key + "':" + value + " - " +
                            "using the default value: " + def);
        }

        return def;
    }
}
