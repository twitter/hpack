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

  private static final byte[] EMPTY = new byte[0];

  /**
   * Construct a header field for a name only that can be encoded with ISO-8859-1
   */
  static HeaderField forNameOnly(String name) {
    return new HeaderField(
        requireNonNull(name).getBytes(ISO_8859_1),
        name,
        EMPTY,
        null);
  }

  /**
   * Construct a header field for a name and value that can be encoded with ISO-8859-1
   */
  static HeaderField forNameValue(String name, String value) {
    return forNameAndParsedValue(name, value, value);
  }

  /**
   * Construct a header field for a name and value that can be encoded with ISO-8859-1
   */
  static HeaderField forNameAndParsedValue(String name, String value, Object annotation) {
    return new HeaderField(
        requireNonNull(name).getBytes(ISO_8859_1),
        name,
        requireNonNull(value).getBytes(ISO_8859_1),
        annotation);
  }

  /**
   * Construct a header field from the decoded bytes received.
   */
  static HeaderField forBytes(byte[] nameBytes, byte[] valueBytes) {
    return forReceivedHeader(nameBytes, null, valueBytes, null);
  }

  /**
   * Construct a header field from the decoded received bytes and an annotation.
   */
  static HeaderField forReceivedHeader(byte[] nameBytes, String name,
                                       byte[] valueBytes, Object annotation) {
    requireNonNull(nameBytes);
    requireNonNull(valueBytes);
    if (name == null) {
      name = new String(nameBytes, ISO_8859_1);
    }
    return new HeaderField(nameBytes, name, valueBytes, annotation);
  }

  // Section 4.1. Calculating Table Size
  // The additional 32 octets account for an estimated
  // overhead associated with the structure.
  static final int HEADER_ENTRY_OVERHEAD = 32;

  static int sizeOf(byte[] name, byte[] value) {
    return name.length + value.length + HEADER_ENTRY_OVERHEAD;
  }

  final byte[] name;
  final String nameString;
  final byte[] value;

  // Used to store an annotation with the header entry
  Object valueAnnotation;

  HeaderField(byte[] name, String nameString, byte[] value, Object valueAnnotation) {
    this.name = requireNonNull(name);
    if (nameString == null) {
      this.nameString = new String(name, ISO_8859_1);
    } else {
      this.nameString = nameString;
    }
    this.value = requireNonNull(value);
    this.valueAnnotation = valueAnnotation;
  }

  int size() {
    return name.length + value.length + HEADER_ENTRY_OVERHEAD;
  }

  @Override
  public int compareTo(HeaderField anotherHeaderField) {
    int ret = compareTo(name, anotherHeaderField.name);
    if (ret == 0) {
      ret = compareTo(value, anotherHeaderField.value);
    }
    return ret;
  }

  private int compareTo(byte[] s1, byte[] s2) {
    int len1 = s1.length;
    int len2 = s2.length;
    int lim = Math.min(len1, len2);

    int k = 0;
    while (k < lim) {
      byte b1 = s1[k];
      byte b2 = s2[k];
      if (b1 != b2) {
        return b1 - b2;
      }
      k++;
    }
    return len1 - len2;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof HeaderField)) {
      return false;
    }
    HeaderField other = (HeaderField) obj;
    boolean nameEquals = HpackUtil.equals(name, other.name);
    boolean valueEquals = HpackUtil.equals(value, other.value);
    return nameEquals && valueEquals;
  }

  @Override
  public String toString() {
    String nameString = new String(name);
    String valueString = new String(value);
    return nameString + ": " + valueString;
  }
}
