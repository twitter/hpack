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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.twitter.hpack.HpackUtil.EMPTY;

final class StaticTable {

  // Appendix B: Static Table
  private static final List<Entry> STATIC_TABLE = java.util.Arrays.asList(
    /*  1 */ new Entry(":authority", EMPTY),
    /*  2 */ new Entry(":method", "GET"),
    /*  3 */ new Entry(":method", "POST"),
    /*  4 */ new Entry(":path", "/"),
    /*  5 */ new Entry(":path", "/index.html"),
    /*  6 */ new Entry(":scheme", "http"),
    /*  7 */ new Entry(":scheme", "https"),
    /*  8 */ new Entry(":status", "200"),
    /*  9 */ new Entry(":status", "500"),
    /* 10 */ new Entry(":status", "404"),
    /* 11 */ new Entry(":status", "403"),
    /* 12 */ new Entry(":status", "400"),
    /* 13 */ new Entry(":status", "401"),
    /* 14 */ new Entry("accept-charset", EMPTY),
    /* 15 */ new Entry("accept-encoding", EMPTY),
    /* 16 */ new Entry("accept-language", EMPTY),
    /* 17 */ new Entry("accept-ranges", EMPTY),
    /* 18 */ new Entry("accept", EMPTY),
    /* 19 */ new Entry("access-control-allow-origin", EMPTY),
    /* 20 */ new Entry("age", EMPTY),
    /* 21 */ new Entry("allow", EMPTY),
    /* 22 */ new Entry("authorization", EMPTY),
    /* 23 */ new Entry("cache-control", EMPTY),
    /* 24 */ new Entry("content-disposition", EMPTY),
    /* 25 */ new Entry("content-encoding", EMPTY),
    /* 26 */ new Entry("content-language", EMPTY),
    /* 27 */ new Entry("content-length", EMPTY),
    /* 28 */ new Entry("content-location", EMPTY),
    /* 29 */ new Entry("content-range", EMPTY),
    /* 30 */ new Entry("content-type", EMPTY),
    /* 31 */ new Entry("cookie", EMPTY),
    /* 32 */ new Entry("date", EMPTY),
    /* 33 */ new Entry("etag", EMPTY),
    /* 34 */ new Entry("expect", EMPTY),
    /* 35 */ new Entry("expires", EMPTY),
    /* 36 */ new Entry("from", EMPTY),
    /* 37 */ new Entry("host", EMPTY),
    /* 38 */ new Entry("if-match", EMPTY),
    /* 39 */ new Entry("if-modified-since", EMPTY),
    /* 40 */ new Entry("if-none-match", EMPTY),
    /* 41 */ new Entry("if-range", EMPTY),
    /* 42 */ new Entry("if-unmodified-since", EMPTY),
    /* 43 */ new Entry("last-modified", EMPTY),
    /* 44 */ new Entry("link", EMPTY),
    /* 45 */ new Entry("location", EMPTY),
    /* 46 */ new Entry("max-forwards", EMPTY),
    /* 47 */ new Entry("proxy-authenticate", EMPTY),
    /* 48 */ new Entry("proxy-authorization", EMPTY),
    /* 49 */ new Entry("range", EMPTY),
    /* 50 */ new Entry("referer", EMPTY),
    /* 51 */ new Entry("refresh", EMPTY),
    /* 52 */ new Entry("retry-after", EMPTY),
    /* 53 */ new Entry("server", EMPTY),
    /* 54 */ new Entry("set-cookie", EMPTY),
    /* 55 */ new Entry("strict-transport-security", EMPTY),
    /* 56 */ new Entry("transfer-encoding", EMPTY),
    /* 57 */ new Entry("user-agent", EMPTY),
    /* 58 */ new Entry("vary", EMPTY),
    /* 59 */ new Entry("via", EMPTY),
    /* 60 */ new Entry("www-authenticate", EMPTY)
  );

  private static final Map<String, Integer> STATIC_INDEX_BY_NAME = createMap();

  private StaticTable() {}

  private static Map<String, Integer> createMap() {
    HashMap<String, Integer> ret = new HashMap<String, Integer>();
    for (int i = STATIC_TABLE.size() - 1; i >= 0; i--) {
      Entry entry = STATIC_TABLE.get(i);
      ret.put(entry.name, i);
    }
    return ret;
  }

  static int getIndex(String name) {
    Integer i = STATIC_INDEX_BY_NAME.get(name);
    if (i != null) {
      return i + 1;
    }
    return -1;
  }

  static int getIndex(String name, String value) {
    Integer index = STATIC_INDEX_BY_NAME.get(name);
    if (index == null) {
      return -1;
    }

    while (index < STATIC_TABLE.size()) {
      Entry entry = STATIC_TABLE.get(index);
      if (!entry.name.equals(name)) {
        break;
      }
      if (entry.value.equals(value)) {
        return index + 1;
      }
      index++;
    }

    return -1;
  }

  static Entry getEntry(int index) {
    return STATIC_TABLE.get(index - 1);
  }

  static int size() {
    return STATIC_TABLE.size();
  }

  static final class Entry {

    private final String name;
    private final String value;

    public Entry(String name, String value) {
      this.name = name;
      this.value = value;
    }

    public String getName() {
      return name;
    }

    public String getValue() {
      return value;
    }
  }
}
