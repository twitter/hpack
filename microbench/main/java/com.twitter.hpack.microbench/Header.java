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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Helper class representing a single header entry. Used by the benchmarks.
 */
class Header {
    final byte[] name;
    final byte[] value;

    Header(byte[] name, byte[] value) {
        this.name = name;
        this.value = value;
    }

    /**
     * Creates a number of random headers with the given name/value lengths.
     */
    static List<Header> createHeaders(int numHeaders, int nameLength, int valueLength) {
        List<Header> headers = new ArrayList<Header>(numHeaders);
        Random r = new Random();
        for (int i = 0; i < numHeaders; ++i) {
            byte[] name = new byte[nameLength];
            r.nextBytes(name);
            byte[] value = new byte[valueLength];
            r.nextBytes(value);
            headers.add(new Header(name, value));
        }
        return headers;
    }
}
