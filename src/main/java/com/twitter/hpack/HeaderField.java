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

import static java.util.Objects.requireNonNull;

class HeaderField implements Comparable<HeaderField> {

  // Section 3.3.1: Maximum Table Size
  // The 32 octets are an accounting for the entry structure overhead.
  static final int HEADER_ENTRY_OVERHEAD = 32;

  final String name;
  final String value;

  final int nameLength;
  final int valueLength;

  private int hash; // Default to 0

  // This constructor can only be used if name and value
  // do not contain any multi-byte characters.
  HeaderField(String name, String value) {
    this(name, value, name.length(), value.length());
  }

  HeaderField(String name, String value, int nameLength, int valueLength) {
    this.name = requireNonNull(name);
    this.value = requireNonNull(value);
    this.nameLength = nameLength;
    this.valueLength = valueLength;
  }

  int size() {
    return nameLength + valueLength + HEADER_ENTRY_OVERHEAD;
  }

  @Override
  public int compareTo(HeaderField anotherHeaderField) {
    int ret = name.compareTo(anotherHeaderField.name);
    if (ret == 0) {
      ret = value.compareTo(anotherHeaderField.value);
    }
    return ret;
  }

  @Override
  public int hashCode() {
    int h = hash;
    if (h == 0 && name.length() + value.length() > 0) {
      for (int i = 0; i < name.length(); i++) {
        h = 31 * h + name.charAt(i);
      }
      for (int i = 0; i < value.length(); i++) {
        h = 31 * h + value.charAt(i);
      }
      hash = h;
    }
    return h;
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
    return name + ": " + value;
  }
}
