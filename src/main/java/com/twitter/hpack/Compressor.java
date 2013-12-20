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
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.twitter.hpack.HpackUtil.ReferenceHeader;

/**
 * <a href="http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-03">
 * HPACK: HTTP/2.0 Header Compression</a>
 */
public class Compressor {

  private List<ReferenceHeader> headerTable;
  private int headerTableSize;

  public Compressor(boolean server) {
    if (server) {
      headerTable = HpackUtil.newResponseTable();
      headerTableSize = HpackUtil.RESPONSE_TABLE_SIZE;
    } else {
      headerTable = HpackUtil.newRequestTable();
      headerTableSize = HpackUtil.REQUEST_TABLE_SIZE;
    }
  }

  /*
   * Unsigned Little Endian Base 128 Variable-Length Integer Encoding
   */
  private static void encodeULE128(OutputStream out, int length) throws IOException {
    while (true) {
      if ((length & ~0x7F) == 0) {
        out.write(length);
        return;
      } else {
        out.write((length & 0x7F) | 0x80);
        length >>>= 7;
      }
    }
  }

  private static void encodeLiteralString(OutputStream out, String string) throws IOException {
    byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
    encodeULE128(out, bytes.length);
    out.write(bytes);
  }

  public void encodeHeader(OutputStream out, String name, String value) throws IOException {
    out.write(0x60);
    encodeLiteralString(out, name);
    encodeLiteralString(out, value);
  }
}
