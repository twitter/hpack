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

import org.junit.Test;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.File;
import java.util.logging.Logger;

/**
 * Base class for all JMH benchmarks.
 */
@Warmup(iterations = AbstractMicrobenchmarkBase.DEFAULT_WARMUP_ITERATIONS)
@Measurement(iterations = AbstractMicrobenchmarkBase.DEFAULT_MEASURE_ITERATIONS)
@State(Scope.Thread)
public abstract class AbstractMicrobenchmarkBase {
    private static final Logger logger = Logger.getLogger(AbstractMicrobenchmarkBase.class.getName());

    protected static final int DEFAULT_WARMUP_ITERATIONS = 10;
    protected static final int DEFAULT_MEASURE_ITERATIONS = 10;
    protected static final String[] JVM_ARGS = {
            "-server", "-dsa", "-da", "-XX:+AggressiveOpts", "-XX:+UseBiasedLocking",
            "-XX:+UseFastAccessorMethods", "-XX:+OptimizeStringConcat",
            "-XX:+HeapDumpOnOutOfMemoryError"};

    protected ChainedOptionsBuilder newOptionsBuilder() throws Exception {
        String className = getClass().getSimpleName();

        ChainedOptionsBuilder runnerOptions = new OptionsBuilder()
                .include(".*" + className + ".*")
                .jvmArgs(jvmArgs());

        if (getWarmupIterations() > 0) {
            runnerOptions.warmupIterations(getWarmupIterations());
        }

        if (getMeasureIterations() > 0) {
            runnerOptions.measurementIterations(getMeasureIterations());
        }

        if (getReportDir() != null) {
            String filePath = getReportDir() + className + ".json";
            File file = new File(filePath);
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

    protected int getWarmupIterations() {
        return getIntProperty("warmupIterations", -1);
    }

    protected int getMeasureIterations() {
        return getIntProperty("measureIterations", -1);
    }

    protected String getReportDir() {
        return System.getProperty("perfReportDir");
    }

    private static int getIntProperty(String key, int def) {
        String value = System.getProperty(key);
        if (value == null) {
            return def;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warning(
                    "Unable to parse the integer system property '" + key + "':" + value + " - " +
                            "using the default value: " + def);
        }

        return def;
    }
}
