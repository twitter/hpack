/*
 * Copyright 2013 Twitter, Inc.
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
package com.twitter.hpack;

import java.io.IOException;
import java.io.OutputStream;

final class HuffmanEncoder {

  private final int[] codes;
  private final byte[] lengths;

  public HuffmanEncoder(int[] codes, byte[] lengths) {
    this.codes = codes;
    this.lengths = lengths;
  }

  public void encode(byte[] data, OutputStream out) throws IOException {
    long current = 0;
    int n = 0;

    for (int i = 0; i < data.length; i++) {
      int b = data[i] & 0xFF;
      int code = codes[b];
      int nbits = lengths[b];

      current <<= nbits;
      current |= code;
      n += nbits;

      while (n >= 8) {
        n -= 8;
        out.write(((int)(current >> n)));
      }
    }

    if (n > 0) {
      current <<= (8 - n);
      current |= (0xFF >>> n);
      out.write((int)current);
    }
  }

  public int getEncodedLength(byte[] bytes) {
    long len = 0;

    for (int i = 0; i < bytes.length; i++) {
      int b = bytes[i] & 0xFF;
      len += lengths[b];
    }

    return (int)((len + 7) >> 3);
  }
}
