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
import java.util.HashMap;
import java.util.Map;

import com.twitter.hpack.HpackUtil.ReferenceHeader;

import static com.twitter.hpack.HpackUtil.HEADER_ENTRY_OVERHEAD;
import static com.twitter.hpack.HpackUtil.MAX_HEADER_TABLE_SIZE;

public final class Compressor {

  // For testing
  private final boolean useIndexing;
  private final boolean forceHuffmanOn;
  private final boolean forceHuffmanOff;

  // the huffman encoder used to encode literal values
  private final HuffmanEncoder huffmanEncoder;

  // a circular queue of header entries
  private HeaderEntry[] headerTable;
  private int head;
  private int size;

  // for each entry in the header table, maps the name to a list of entries with the same name
  private Map<String, HeaderEntry> headerByName = new HashMap<String, HeaderEntry>();

  // maximum allowable header table size in bytes
  private int maxHeaderTableSize;

  // estimate of the current header table size in bytes
  private int headerTableSize;

  public Compressor(boolean server) {
    this(server, MAX_HEADER_TABLE_SIZE);
  }

  public Compressor(boolean server, int maxHeaderTableSize) {
    this(server, maxHeaderTableSize, true, false, false);
  }

  /**
   * Constructor for testing only.
   */
  Compressor(
      boolean server,
      int maxHeaderTableSize,
      boolean useIndexing,
      boolean forceHuffmanOn,
      boolean forceHuffmanOff
  ) {
    this.huffmanEncoder = server ? HpackUtil.RESPONSE_ENCODER : HpackUtil.REQUEST_ENCODER;
    this.useIndexing = useIndexing;
    this.maxHeaderTableSize = maxHeaderTableSize;
    this.forceHuffmanOn = forceHuffmanOn;
    this.forceHuffmanOff = forceHuffmanOff;

    this.headerTable = new HeaderEntry[maxHeaderTableSize / HEADER_ENTRY_OVERHEAD + 1];
  }

  /**
   * Encodes a single header into the header block.
   **/
  public void encodeHeader(OutputStream out, String name, String value) throws IOException {

    name = name.toLowerCase();

    int headerSize = ReferenceHeader.sizeOf(name, value);

    // If the headerSize is greater than the max table size then it must be encoded literally
    if (headerSize > maxHeaderTableSize) {
      int index = getIndexByName(name);
      encodeLiteral(out, name, value, false, index);
      return;
    }

    int index = getHeaderTableIndex(name, value);
    if (index != -1) {
      HeaderEntry entry = getHeaderEntry(index);

      if (entry.inReferenceSet) {
        if (!entry.emitted) {
          entry.emitted = true;
        } else {
          // The header was in the reference set and already emitted.

          // remove from reference set, emit, remove from reference set, emit
          for (int i = 0; i < 4; i++) {
            encodeInteger(out, 0x80, 7, index);
          }

          // inReferenceSet will be set to true after the header block is completed.  In the meantime,
          // inReferenceSet == false && emitted = true represents the state that we've seen this header at least
          // twice in this header block.
          entry.inReferenceSet = false;
        }
      } else {
        if (entry.emitted) {
          // first remove it from the reference set
          encodeInteger(out, 0x80, 7, index);
        }

        // Section 4.2 - Indexed Header Field
        encodeInteger(out, 0x80, 7, index);
        entry.emitted = true;
      }
    } else {
      index = StaticTable.getIndex(name, value);
      if (index != -1) {
        index += size;
        if (useIndexing) {
          ensureCapacity(out, headerSize);
          add(name, value);
        }
      }

      if (index != -1) {
        // Section 4.2 - Indexed Header Field
        encodeInteger(out, 0x80, 7, index);
      } else {
        int nameIndex = getIndexByName(name);
        if (useIndexing) {
          ensureCapacity(out, headerSize);
        }
        encodeLiteral(out, name, value, useIndexing, nameIndex);
        if (useIndexing) {
          add(name, value);
        }
      }
    }
  }

  /**
   * Must be called after all headers in a header block have been encoded.
   **/
  public void endHeaders(OutputStream out) throws IOException {
    // encode removed headers
    for (int index = 1; index <= size; index++) {
      HeaderEntry entry = getHeaderEntry(index);
      if (entry.emitted && !entry.inReferenceSet) {
        entry.inReferenceSet = true;
      }
      if (entry.inReferenceSet && !entry.emitted) {
        encodeInteger(out, 0x80, 7, index);
        entry.inReferenceSet = false;
      }
      entry.emitted = false;
    }
  }

  /**
   * Removes all entries from the reference set.
   **/
  public void clearReferenceSet(OutputStream out) throws IOException {
    out.write(0x80);
    // encode removed headers
    for (int index = 1; index <= size; index++) {
      HeaderEntry entry = getHeaderEntry(index);
      entry.inReferenceSet = false;
    }
  }

  /**
   * Resizes the header table.  If the table is reduced in size, entries may be evicted.
   *
   * Section 3.3.3.  Entry Eviction When Header Table Size Changes
   **/
  public void setHeaderTableSize(OutputStream out, int size) throws IOException {
    maxHeaderTableSize = size;
    ensureCapacity(out, 0);

    HeaderEntry[] tmp = new HeaderEntry[maxHeaderTableSize / HEADER_ENTRY_OVERHEAD + 1];

    for (int i = 1; i <= size; i++) {
      tmp[i] = getHeaderEntry(i);
      tmp[i].index = i - 1;
    }
    head = 0;

    headerTable = tmp;
  }

  /**
   * @param mask  A mask to be applied to the first byte
   * @param n     The number of prefix bits
   * @param i     The value to encode
   */
  private static void encodeInteger(OutputStream out, int mask, int n, int i) throws IOException {
    if (n < 0 || n > 8) {
      throw new IllegalArgumentException("N: " + n);
    }

    int nbits = 0xFF >>> (8 - n);

    if (i < nbits) {
      out.write(mask | i);
    } else {
      out.write(mask | nbits);
      int length = i - nbits;
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
  }

  /**
   * 4.3.1. Literal Header Field without Indexing
   * 4.3.2. Literal Header Field with Incremental Indexing
   */
  private void encodeLiteral(OutputStream out, String name, String value, boolean indexing, int nameIndex)
      throws IOException {

    encodeInteger(out, indexing ? 0x00 : 0x40, 6, nameIndex == -1 ? 0 : nameIndex);

    if (nameIndex == -1) {
      encodeStringLiteral(out, name);
    }

    encodeStringLiteral(out, value);
  }

  /**
   * Encode string literal according to 4.1.2.
   *
   * @param out The OutputStream to encode into
   * @param string The string to encode
   */
  private void encodeStringLiteral(OutputStream out, String string) throws IOException {
    byte[] bytes = string.getBytes(StandardCharsets.UTF_8);

    int huffmanLength = huffmanEncoder.getEncodedLength(bytes);

    if ((huffmanLength < bytes.length && !forceHuffmanOff) || forceHuffmanOn) {
      encodeInteger(out, 0x80, 7, huffmanLength);
      huffmanEncoder.encode(bytes, out);
    } else {
      encodeInteger(out, 0x00, 7, bytes.length);
      out.write(bytes, 0, bytes.length);
    }
  }

  /**
   * Lookup an entry in the header table by index.
   **/
  private HeaderEntry getHeaderEntry(int index) {
    return headerTable[(head + index - 1) % headerTable.length];
  }

  /**
   * Attempt to lookup an entry in the header table with name, value.
   *
   * @return  If found, the index, otherwise -1.
   **/
  private int getHeaderTableIndex(String name, String value) {
    HeaderEntry h = headerByName.get(name);
    if (h == null) {
      return -1;
    }

    int ret = -1;
    while (h != null) {
      if (equals(h.value, value)) {
        // don't short-circuit in order to avoid leaking timing information
        if (ret == -1) {
          ret = headerIndex(h.index);
        }
      }
      h = h.nextName;
    }

    return ret;
  }

  /**
   * Attempt to lookup a value in the header table or the static table by name.
   *
   * @return  If found, the index, otherwise -1.
   **/
  private int getIndexByName(String name) {
    int index = getHeaderIndexByName(name);

    if (index == -1) {
      index = StaticTable.getIndex(name);
      if (index >= 0) {
        index += size;
      }
    }

    return index;
  }

  /**
   * Attempt to lookup a value in the header table by name.
   *
   * @return  If found, the index, otherwise -1.
   **/
  private int getHeaderIndexByName(String name) {
    HeaderEntry h = headerByName.get(name);
    if (h == null) {
      return -1;
    }
    return headerIndex(h.index);
  }

  /**
   * Maps an index in the header table (circular queue) to an actual header index
   */
  private int headerIndex(int tableIndex) {
    if (tableIndex >= head) {
      return (tableIndex - head) + 1;
    } else {
      return ((headerTable.length - head) + tableIndex) + 1;
    }
  }

  /**
   * Adds a new header entry with the given values to the header table.  Evicts the oldest entries in the table to make
   * room, if necessary.
   **/
  private void add(String name, String value) throws IOException {

    if (head == 0) {
      head = headerTable.length - 1;
    } else {
      head = head - 1;
    }

    if (headerTable[head] == null) {
      headerTable[head] = new HeaderEntry();
    }

    HeaderEntry entry = headerTable[head];
    entry.name = name;
    entry.value = value;
    entry.inReferenceSet = false;
    entry.emitted = true;
    entry.index = head;

    headerTableSize += ReferenceHeader.sizeOf(name, value);
    size++;

    // Add a name -> entry mapping
    addHeaderNameMapping(entry);
  }

  /**
   * Ensure that the header table has enough room to hold 'headerSize' more bytes.  Will evict the oldest entries until
   * sufficient space is available.
   **/
  private void ensureCapacity(OutputStream out, int headerSize) throws IOException {
    while (size > 0 &&
        (headerTableSize + headerSize > maxHeaderTableSize || size == headerTable.length))
    {
      evict(out);
    }
  }

  /**
   * Removes the oldest entry from the header table.  If the entry has been emitteed in this header set, its index is 
   * emitted twice in order to remove it from the reference set and emit it.
   **/
  private void evict(OutputStream out) throws IOException {
    HeaderEntry entry = getHeaderEntry(size);

    removeHeaderNameMapping(entry.name);

    if (entry.inReferenceSet && entry.emitted) {
      // evict the entry from the reference set and emit it
      encodeInteger(out, 0x80, 7, headerIndex(entry.index));
      encodeInteger(out, 0x80, 7, headerIndex(entry.index));
    }

    headerTableSize -= ReferenceHeader.sizeOf(entry.name, entry.value);
    size--;

    entry.name = null;
    entry.value = null;
  }

  /**
   * Adds a name -> entry mapping.  If a mapping already exists, the new mapping is prepended to the list of mappings 
   * by name.
   **/
  private void addHeaderNameMapping(HeaderEntry entry) {
    HeaderEntry h = headerByName.remove(entry.name);
    if (h == null) {
      headerByName.put(entry.name, entry);
    } else {
      entry.nextName = h;
      headerByName.put(entry.name, entry);
    }
  }

  /**
   * Removes the last value in the list of values mapped to a name.  If there is only 1 value then the name is removed
   * from the headerByName map.
   **/
  private void removeHeaderNameMapping(String name) {
    HeaderEntry h = headerByName.get(name);
    HeaderEntry prev = null;
    while (h.nextName != null) {
      prev = h;
      h = h.nextName;
    }
    if (prev == null) {
      headerByName.remove(name);
    } else {
      prev.nextName = null;
    }
  }

  /**
   * A string compare that doesn't leak timing information:  http://codahale.com/a-lesson-in-timing-attacks/
   */
  private static boolean equals(String s1, String s2) {
    if (s1.length() != s2.length()) {
      return false;
    }

    char c = 0;

    for (int i = 0; i < s1.length(); i++) {
      c |= (s1.charAt(i) ^ s2.charAt(i));
    }

    return c == 0;
  }

  private static class HeaderEntry extends ReferenceHeader {
    HeaderEntry nextName;
    int index;
  }
}
