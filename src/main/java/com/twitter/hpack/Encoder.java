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

public final class Encoder {

  // For testing
  private final boolean useIndexing;
  private final boolean forceHuffmanOn;
  private final boolean forceHuffmanOff;

  // the huffman encoder used to encode literal values
  private final HuffmanEncoder huffmanEncoder;

  private EncoderTable headerTable;

  public Encoder(boolean server) {
    this(server, HpackUtil.DEFAULT_HEADER_TABLE_SIZE);
  }

  public Encoder(boolean server, int maxHeaderTableSize) {
    this(server, maxHeaderTableSize, true, false, false);
  }

  /**
   * Constructor for testing only.
   */
  Encoder(
      boolean server,
      int maxHeaderTableSize,
      boolean useIndexing,
      boolean forceHuffmanOn,
      boolean forceHuffmanOff
  ) {
    this.huffmanEncoder = server ? HpackUtil.RESPONSE_ENCODER : HpackUtil.REQUEST_ENCODER;
    this.useIndexing = useIndexing;
    this.forceHuffmanOn = forceHuffmanOn;
    this.forceHuffmanOff = forceHuffmanOff;
    this.headerTable = new EncoderTable(maxHeaderTableSize);
  }

  /**
   * Encodes a single header into the header block.
   **/
  public void encodeHeader(OutputStream out, String name, String value) throws IOException {

    ReferenceHeader header = new ReferenceHeader(name, value);
    int headerSize = header.size();

    // If the headerSize is greater than the max table size then it must be encoded literally
    if (headerSize > headerTable.capacity()) {
      int nameIndex = getNameIndex(name);
      encodeLiteral(out, name, value, false, nameIndex);
      return;
    }

    int headerTableIndex = headerTable.getIndex(header);
    if (headerTableIndex != -1) {
      ReferenceHeader entry = headerTable.getEntry(headerTableIndex);

      if (entry.inReferenceSet) {
        if (!entry.emitted) {
          entry.emitted = true;
        } else {
          // The header was in the reference set and already emitted.

          // remove from reference set, emit, remove from reference set, emit
          for (int i = 0; i < 4; i++) {
            encodeInteger(out, 0x80, 7, headerTableIndex);
          }

          // inReferenceSet will be set to true after the header block is completed.  In the meantime,
          // inReferenceSet == false && emitted = true represents the state that we've seen this header at least
          // twice in this header block.
          entry.inReferenceSet = false;
        }
      } else {
        if (entry.emitted) {
          // first remove it from the reference set
          encodeInteger(out, 0x80, 7, headerTableIndex);
        }

        // Section 4.2 - Indexed Header Field
        encodeInteger(out, 0x80, 7, headerTableIndex);
        entry.emitted = true;
      }
    } else {
      int staticTableIndex = StaticTable.getIndex(header);
      if (staticTableIndex != -1 && useIndexing) {
        // Section 4.2 - Indexed Header Field
        int nameIndex = staticTableIndex + headerTable.length();
        ensureCapacity(out, headerSize);
        add(name, value);
        encodeInteger(out, 0x80, 7, nameIndex);
      } else {
        int nameIndex = getNameIndex(name);
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
    for (int index = 1; index <= headerTable.length(); index++) {
      ReferenceHeader entry = headerTable.getEntry(index);
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

  public void clearReferenceSet(OutputStream out) throws IOException {
    out.write(0x80);
    // encode removed headers
    for (int index = 1; index <= headerTable.length(); index++) {
      ReferenceHeader entry = headerTable.getEntry(index);
      entry.inReferenceSet = false;
    }
  }

  public void setHeaderTableSize(OutputStream out, int size) throws IOException {
    int neededSize = headerTable.size() - size;
    if (neededSize > 0) {
      ensureCapacity(out, neededSize);
    }
    headerTable.setCapacity(size);
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
   * @param out The out to encode into
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

  private int getNameIndex(String name) {
    int index = headerTable.getIndex(name);
    if (index == -1) {
      index = StaticTable.getIndex(name);
      if (index >= 0) {
        index += headerTable.length();
      }
    }
    return index;
  }

  /**
   * Adds a new header entry with the given values to the header table.  Evicts the oldest entries in the table to make
   * room, if necessary.
   **/
  private void add(String name, String value) throws IOException {
    ReferenceHeader referenceHeader = new ReferenceHeader(name, value);
    referenceHeader.inReferenceSet = false;
    referenceHeader.emitted = true;
    headerTable.add(referenceHeader);
  }

  /**
   * Ensure that the header table has enough room to hold 'headerSize' more bytes.
   * Removes the oldest entry from the header table until sufficient space is available.
   * If the entry is in the reference set and is marked as "to-be-emitted", its index is
   * emitted in order to tell the peer to emit the header before it is removed from its
   * header table as well.
   */
  private void ensureCapacity(OutputStream out, int headerSize) throws IOException {
    while (headerTable.size() + headerSize > headerTable.capacity()) {
      int index = headerTable.length();
      if (index == 0) {
        break;
      }
      ReferenceHeader removed = headerTable.remove();
      if (removed.inReferenceSet && removed.emitted) {
        // evict the entry from the reference set and emit it
        encodeInteger(out, 0x80, 7, index);
        encodeInteger(out, 0x80, 7, index);
      }
    }
  }

  private static class EncoderTable extends HeaderTable<ReferenceHeader> {
    // a map of header field to array offset
    private Map<ReferenceHeader, Integer> headerMap;

    public EncoderTable(int initialCapacity) {
      super(initialCapacity);
    }

    /**
     * Returns the lowest index value for the header field in the header table.
     * Returns -1 if the header field is not in the header table.
     */
    public int getIndex(ReferenceHeader header) {
      Integer offset = headerMap.get(header);
      if (offset == null) {
        return -1;
      }
      return getIndex(offset);
    }

    @Override
    public void add(ReferenceHeader header) {
      int index = head;
      super.add(header);
      if (length() > 0) {
        // header was successfully added
        headerMap.put(header, index);
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setCapacity(int capacity) {
      super.setCapacity(capacity);
      headerMap = new HashMap<ReferenceHeader, Integer>(headerTable.length, 1);
      for (int i = tail; i < head; i++) {
        headerMap.put((ReferenceHeader) headerTable[i], i);
      }
    }

    @Override
    public ReferenceHeader remove() {
      ReferenceHeader removed = super.remove();
      headerMap.remove(removed);
      return removed;
    }

    @Override
    public void clear() {
      super.clear();
      headerMap.clear();
    }
  }
}
