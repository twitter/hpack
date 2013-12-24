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

final class Header implements Comparable<Header> {
  final String name;
  final String value;

  Header(String name, String value) {
    this.name = requireNonNull(name);
    this.value = requireNonNull(value);
  }

  @Override
  public int compareTo(Header anotherHeader) {
    int ret = name.compareTo(anotherHeader.name);
    if (ret == 0) {
      ret = value.compareTo(anotherHeader.value);
    }
    return ret;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof Header)) {
      return false;
    }
    Header other = (Header) o;
    return name.equals(other.name) && value.equals(other.value);
  }

  @Override
  public String toString() {
    return name + ": " + value;
  }
}
