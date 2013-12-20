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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

final class TestHeaders implements HeaderListener {

  private HashMap<String, List<String>> headersByName = new HashMap<String, List<String>>();

  @Override
  public void emitHeader(String name, String value) {
    if (value.length() == 0) {
      if (headersByName.containsKey(name)) {
        return;
      }
    }
    add(name, value);
  }

  void add(String name, String value) {
    List<String> l = headersByName.get(name);
    if (l == null) {
      l = new ArrayList<String>();
      headersByName.put(name, l);
    }
    l.add(value);
  }

  Collection<String> names() {
    return headersByName.keySet();
  }

  String get(String name) {
    List<String> l = getAll(name);
    if (l == null) {
      return null;
    } else {
      return l.get(0);
    }
  }

  List<String> getAll(String name) {
    List<String> l = headersByName.get(name);
    if (l == null) {
      return Collections.emptyList();
    } else {
      return l;
    }
  }

  void clear() {
    headersByName.clear();
  }

  public boolean equals(Object o) {
    TestHeaders other = (TestHeaders) o;
    return headersByName.equals(other.headersByName);
  }

  public String toString() {
    return headersByName.toString();
  }
}
