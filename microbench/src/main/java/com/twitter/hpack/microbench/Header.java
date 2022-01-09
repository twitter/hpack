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
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_";

    final byte[] name;
    final byte[] value;

    Header(final byte[] name, final byte[] value) {
        this.name = name;
        this.value = value;
    }

    /**
     * Creates a number of random headers with the given name/value lengths.
     */
    static List<Header> createHeaders(final int numHeaders, final int nameLength, final int valueLength, final boolean limitToAscii) {
        final List<Header> headers = new ArrayList<Header>(numHeaders);
        final Random r = new Random();
        for (int i = 0; i < numHeaders; ++i) {
            final byte[] name = randomBytes(new byte[nameLength], limitToAscii);
            final byte[] value = randomBytes(new byte[valueLength], limitToAscii);
            headers.add(new Header(name, value));
        }
        return headers;
    }

    private static byte[] randomBytes(final byte[] bytes, final boolean limitToAscii) {
        final Random r = new Random();
        if (limitToAscii) {
            for (int index=0; index < bytes.length; ++index) {
                final int charIndex = r.nextInt(ALPHABET.length());
                bytes[index] = (byte) ALPHABET.charAt(charIndex);
            }
        } else {
            r.nextBytes(bytes);
        }
        return bytes;
    }
}
