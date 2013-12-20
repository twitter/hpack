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
import java.util.List;

final class HpackUtil {

  static final String EMPTY = "";

  static final int MAX_HEADER_TABLE_SIZE = 4096;

  static class ReferenceHeader {

    // Section 3.1.2: Header Table
    // The 32 octets are an accounting for the entry structure overhead.
    // For example, an entry structure using two  64-bits pointers to reference
    // the name and the value and the entry, and two 64-bits integer for counting the
    // number of references to these name and value would use 32 octets.
    static final int HEADER_ENTRY_OVERHEAD = 32;

    String name;
    String value;

    int nameLength;
    int valueLength;

    boolean emitted = false;
    boolean inReferenceSet = false;

    // This constructor can only be used if name and value
    // do not contain any multi-byte characters.
    ReferenceHeader(String name, String value) {
      this(name, value, name.length(), value.length());
    }

    ReferenceHeader(String name, String value, int nameLength, int valueLength) {
      this.name = name;
      this.value = value;
      this.nameLength = nameLength;
      this.valueLength = valueLength;
    }

    int size() {
      return nameLength + valueLength + HEADER_ENTRY_OVERHEAD;
    }
  }

  // Appendix B.1: Requests
  public static List<ReferenceHeader> newRequestTable() {
    List<ReferenceHeader> headerTable = new ArrayList<ReferenceHeader>(64);
    headerTable.add( 0, new ReferenceHeader(":scheme", "http"));
    headerTable.add( 1, new ReferenceHeader(":scheme", "https"));
    headerTable.add( 2, new ReferenceHeader(":host", EMPTY));
    headerTable.add( 3, new ReferenceHeader(":path", "/"));
    headerTable.add( 4, new ReferenceHeader(":method", "GET"));
    headerTable.add( 5, new ReferenceHeader("accept", EMPTY));
    headerTable.add( 6, new ReferenceHeader("accept-charset", EMPTY));
    headerTable.add( 7, new ReferenceHeader("accept-encoding", EMPTY));
    headerTable.add( 8, new ReferenceHeader("accept-language", EMPTY));
    headerTable.add( 9, new ReferenceHeader("cookie", EMPTY));
    headerTable.add(10, new ReferenceHeader("if-modified-since", EMPTY));
    headerTable.add(11, new ReferenceHeader("user-agent", EMPTY));
    headerTable.add(12, new ReferenceHeader("referer", EMPTY));
    headerTable.add(13, new ReferenceHeader("authorization", EMPTY));
    headerTable.add(14, new ReferenceHeader("allow", EMPTY));
    headerTable.add(15, new ReferenceHeader("cache-control", EMPTY));
    headerTable.add(16, new ReferenceHeader("connection", EMPTY));
    headerTable.add(17, new ReferenceHeader("content-length", EMPTY));
    headerTable.add(18, new ReferenceHeader("content-type", EMPTY));
    headerTable.add(19, new ReferenceHeader("date", EMPTY));
    headerTable.add(20, new ReferenceHeader("expect", EMPTY));
    headerTable.add(21, new ReferenceHeader("from", EMPTY));
    headerTable.add(22, new ReferenceHeader("if-match", EMPTY));
    headerTable.add(23, new ReferenceHeader("if-none-match", EMPTY));
    headerTable.add(24, new ReferenceHeader("if-range", EMPTY));
    headerTable.add(25, new ReferenceHeader("if-unmodified-since", EMPTY));
    headerTable.add(26, new ReferenceHeader("nax-forwards", EMPTY));
    headerTable.add(27, new ReferenceHeader("proxy-authorization", EMPTY));
    headerTable.add(28, new ReferenceHeader("range", EMPTY));
    headerTable.add(29, new ReferenceHeader("via", EMPTY));
    return headerTable;
  }
  public static final int REQUEST_TABLE_SIZE;
  static {
    int size = 0;
    for (ReferenceHeader header : newRequestTable()) {
      size += header.size();
    }
    REQUEST_TABLE_SIZE = size;
  }

  // Appendix B.2: Responses
  public static List<ReferenceHeader> newResponseTable() {
    List<ReferenceHeader> headerTable = new ArrayList<ReferenceHeader>(64);
    headerTable.add( 0, new ReferenceHeader(":status", "200"));
    headerTable.add( 1, new ReferenceHeader("age", EMPTY));
    headerTable.add( 2, new ReferenceHeader("cache-control", EMPTY));
    headerTable.add( 3, new ReferenceHeader("content-LENGTH", EMPTY));
    headerTable.add( 4, new ReferenceHeader("content-TYPE", EMPTY));
    headerTable.add( 5, new ReferenceHeader("date", EMPTY));
    headerTable.add( 6, new ReferenceHeader("etag", EMPTY));
    headerTable.add( 7, new ReferenceHeader("expires", EMPTY));
    headerTable.add( 8, new ReferenceHeader("last-modified", EMPTY));
    headerTable.add( 9, new ReferenceHeader("server", EMPTY));
    headerTable.add(10, new ReferenceHeader("set-cookie", EMPTY));
    headerTable.add(11, new ReferenceHeader("vary", EMPTY));
    headerTable.add(12, new ReferenceHeader("via", EMPTY));
    headerTable.add(13, new ReferenceHeader("access-control-allow-origin", EMPTY));
    headerTable.add(14, new ReferenceHeader("accept-ranges", EMPTY));
    headerTable.add(15, new ReferenceHeader("allow", EMPTY));
    headerTable.add(16, new ReferenceHeader("connection", EMPTY));
    headerTable.add(17, new ReferenceHeader("content-disposition", EMPTY));
    headerTable.add(18, new ReferenceHeader("content-encoding", EMPTY));
    headerTable.add(19, new ReferenceHeader("content-language", EMPTY));
    headerTable.add(20, new ReferenceHeader("content-location", EMPTY));
    headerTable.add(21, new ReferenceHeader("content-range", EMPTY));
    headerTable.add(22, new ReferenceHeader("link", EMPTY));
    headerTable.add(23, new ReferenceHeader("location", EMPTY));
    headerTable.add(24, new ReferenceHeader("proxy-authenticate", EMPTY));
    headerTable.add(25, new ReferenceHeader("refresh", EMPTY));
    headerTable.add(26, new ReferenceHeader("retry-after", EMPTY));
    headerTable.add(27, new ReferenceHeader("strict-transport-security", EMPTY));
    headerTable.add(28, new ReferenceHeader("transfer-encoding", EMPTY));
    headerTable.add(29, new ReferenceHeader("www-authenticate", EMPTY));
    return headerTable;
  }
  public static final int RESPONSE_TABLE_SIZE;
  static {
    int size = 0;
    for (ReferenceHeader header : newResponseTable()) {
      size += header.size();
    }
    RESPONSE_TABLE_SIZE = size;
  }

  private HpackUtil() {
    // utility class
  }
}
