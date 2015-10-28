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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.twitter.hpack.HpackUtil.ISO_8859_1;

final class StaticTable {

  // Appendix A: Static Table
  // http://tools.ietf.org/html/rfc7541#appendix-A
  private static final List<HeaderField> STATIC_TABLE = Arrays.asList(
    /*  1 */ HeaderField.forNameOnly(":authority"),
    /*  2 */ HeaderField.forNameValue(":method", "GET"),
    /*  3 */ HeaderField.forNameValue(":method", "POST"),
    /*  4 */ HeaderField.forNameValue(":path", "/"),
    /*  5 */ HeaderField.forNameValue(":path", "/index.html"),
    /*  6 */ HeaderField.forNameValue(":scheme", "http"),
    /*  7 */ HeaderField.forNameValue(":scheme", "https"),
    /*  8 */ HeaderField.forNameAndParsedValue(":status", "200", 200),
    /*  9 */ HeaderField.forNameAndParsedValue(":status", "204", 204),
    /* 10 */ HeaderField.forNameAndParsedValue(":status", "206", 206),
    /* 11 */ HeaderField.forNameAndParsedValue(":status", "304", 304),
    /* 12 */ HeaderField.forNameAndParsedValue(":status", "400", 400),
    /* 13 */ HeaderField.forNameAndParsedValue(":status", "404", 404),
    /* 14 */ HeaderField.forNameAndParsedValue(":status", "500", 500),
    /* 15 */ HeaderField.forNameOnly("accept-charset"),
    /* 16 */ HeaderField.forNameAndParsedValue("accept-encoding", "gzip, deflate",
                              Arrays.asList("gzip", "deflate")),
    /* 17 */ HeaderField.forNameOnly("accept-language"),
    /* 18 */ HeaderField.forNameOnly("accept-ranges"),
    /* 19 */ HeaderField.forNameOnly("accept"),
    /* 20 */ HeaderField.forNameOnly("access-control-allow-origin"),
    /* 21 */ HeaderField.forNameOnly("age"),
    /* 22 */ HeaderField.forNameOnly("allow"),
    /* 23 */ HeaderField.forNameOnly("authorization"),
    /* 24 */ HeaderField.forNameOnly("cache-control"),
    /* 25 */ HeaderField.forNameOnly("content-disposition"),
    /* 26 */ HeaderField.forNameOnly("content-encoding"),
    /* 27 */ HeaderField.forNameOnly("content-language"),
    /* 28 */ HeaderField.forNameOnly("content-length"),
    /* 29 */ HeaderField.forNameOnly("content-location"),
    /* 30 */ HeaderField.forNameOnly("content-range"),
    /* 31 */ HeaderField.forNameOnly("content-type"),
    /* 32 */ HeaderField.forNameOnly("cookie"),
    /* 33 */ HeaderField.forNameOnly("date"),
    /* 34 */ HeaderField.forNameOnly("etag"),
    /* 35 */ HeaderField.forNameOnly("expect"),
    /* 36 */ HeaderField.forNameOnly("expires"),
    /* 37 */ HeaderField.forNameOnly("from"),
    /* 38 */ HeaderField.forNameOnly("host"),
    /* 39 */ HeaderField.forNameOnly("if-match"),
    /* 40 */ HeaderField.forNameOnly("if-modified-since"),
    /* 41 */ HeaderField.forNameOnly("if-none-match"),
    /* 42 */ HeaderField.forNameOnly("if-range"),
    /* 43 */ HeaderField.forNameOnly("if-unmodified-since"),
    /* 44 */ HeaderField.forNameOnly("last-modified"),
    /* 45 */ HeaderField.forNameOnly("link"),
    /* 46 */ HeaderField.forNameOnly("location"),
    /* 47 */ HeaderField.forNameOnly("max-forwards"),
    /* 48 */ HeaderField.forNameOnly("proxy-authenticate"),
    /* 49 */ HeaderField.forNameOnly("proxy-authorization"),
    /* 50 */ HeaderField.forNameOnly("range"),
    /* 51 */ HeaderField.forNameOnly("referer"),
    /* 52 */ HeaderField.forNameOnly("refresh"),
    /* 53 */ HeaderField.forNameOnly("retry-after"),
    /* 54 */ HeaderField.forNameOnly("server"),
    /* 55 */ HeaderField.forNameOnly("set-cookie"),
    /* 56 */ HeaderField.forNameOnly("strict-transport-security"),
    /* 57 */ HeaderField.forNameOnly("transfer-encoding"),
    /* 58 */ HeaderField.forNameOnly("user-agent"),
    /* 59 */ HeaderField.forNameOnly("vary"),
    /* 60 */ HeaderField.forNameOnly("via"),
    /* 61 */ HeaderField.forNameOnly("www-authenticate")
  );

  private static final Map<String, Integer> STATIC_INDEX_BY_NAME = createMap();

  /**
   * The number of header fields in the static table.
   */
  static final int length = STATIC_TABLE.size();

  /**
   * Return the header field at the given index value.
   */
  static HeaderField getEntry(int index) {
    return STATIC_TABLE.get(index - 1);
  }

  /**
   * Returns the lowest index value for the given header field name in the static table.
   * Returns -1 if the header field name is not in the static table.
   */
  static int getIndex(byte[] name) {
    String nameString = new String(name, 0, name.length, ISO_8859_1);
    Integer index = STATIC_INDEX_BY_NAME.get(nameString);
    if (index == null) {
      return -1;
    }
    return index;
  }

  /**
   * Returns the index value for the given header field in the static table.
   * Returns -1 if the header field is not in the static table.
   */
  static int getIndex(byte[] name, byte[] value) {
    int index = getIndex(name);
    if (index == -1) {
      return -1;
    }

    // Note this assumes all entries for a given header field are sequential.
    while (index <= length) {
      HeaderField entry = getEntry(index);
      if (!HpackUtil.equals(name, entry.name)) {
        break;
      }
      if (HpackUtil.equals(value, entry.value)) {
        return index;
      }
      index++;
    }

    return -1;
  }

  // create a map of header name to index value to allow quick lookup
  private static Map<String, Integer> createMap() {
    int length = STATIC_TABLE.size();
    HashMap<String, Integer> ret = new HashMap<String, Integer>(length);
    // Iterate through the static table in reverse order to
    // save the smallest index for a given name in the map.
    for (int index = length; index > 0; index--) {
      HeaderField entry = getEntry(index);
      String name = new String(entry.name, 0, entry.name.length, ISO_8859_1);
      ret.put(name, index);
    }
    return ret;
  }

  // singleton
  private StaticTable() {}
}
