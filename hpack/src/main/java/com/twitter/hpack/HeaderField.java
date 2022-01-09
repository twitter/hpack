/*
 * Copyright 2014 Twitter, Inc.
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

import static com.twitter.hpack.HpackUtil.ISO_8859_1;
import static com.twitter.hpack.HpackUtil.requireNonNull;

class HeaderField implements Comparable<HeaderField> {

  // Section 4.1. Calculating Table Size
  // The additional 32 octets account for an estimated
  // overhead associated with the structure.
  static final int HEADER_ENTRY_OVERHEAD = 32;

  static int sizeOf(final byte[] name, final byte[] value) {
    return name.length + value.length + HEADER_ENTRY_OVERHEAD;
  }

  final byte[] name;
  final byte[] value;

  // This constructor can only be used if name and value are ISO-8859-1 encoded.
  HeaderField(final String name, final String value) {
    this(name.getBytes(ISO_8859_1), value.getBytes(ISO_8859_1));
  }

  HeaderField(final byte[] name, final byte[] value) {
    this.name = requireNonNull(name);
    this.value = requireNonNull(value);
  }

  int size() {
    return name.length + value.length + HEADER_ENTRY_OVERHEAD;
  }

  @Override
  public int compareTo(final HeaderField anotherHeaderField) {
    int ret = compareTo(name, anotherHeaderField.name);
    if (ret == 0) {
      ret = compareTo(value, anotherHeaderField.value);
    }
    return ret;
  }

  private int compareTo(final byte[] s1, final byte[] s2) {
    final int len1 = s1.length;
    final int len2 = s2.length;
    final int lim = Math.min(len1, len2);

    int k = 0;
    while (k < lim) {
      final byte b1 = s1[k];
      final byte b2 = s2[k];
      if (b1 != b2) {
        return b1 - b2;
      }
      k++;
    }
    return len1 - len2;
  }

  @Override
  public boolean equals(final Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof HeaderField)) {
      return false;
    }
    final HeaderField other = (HeaderField) obj;
    final boolean nameEquals = HpackUtil.equals(name, other.name);
    final boolean valueEquals = HpackUtil.equals(value, other.value);
    return nameEquals && valueEquals;
  }

  @Override
  public String toString() {
    final String nameString = new String(name);
    final String valueString = new String(value);
    return nameString + ": " + valueString;
  }
}
