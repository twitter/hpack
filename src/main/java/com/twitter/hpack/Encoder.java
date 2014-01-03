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

import static com.twitter.hpack.HpackUtil.DEFAULT_HEADER_TABLE_SIZE;

public final class Encoder {

  // For testing
  private final boolean useIndexing;
  private final boolean forceHuffmanOn;
  private final boolean forceHuffmanOff;

  // the huffman encoder used to encode literal values
  private final HuffmanEncoder huffmanEncoder;

  // for each entry in the header table, maps the name to a list of entries with the same name
  private Map<String, HeaderEntry> headerByName = new HashMap<String, HeaderEntry>();

  // doubly linked list of header entries
  private HeaderEntry head;
  private HeaderEntry tail;
  
  // maximum allowable header table size in bytes
  private int maxHeaderTableSize;

  // estimate of the current header table size in bytes
  private int headerTableSize;

  public Encoder(boolean server) {
    this(server, DEFAULT_HEADER_TABLE_SIZE);
  }

  public Encoder(boolean server, int maxHeaderTableSize) {
    this(server, maxHeaderTableSize, true, false, false);
  }

  /**
   * Constructor for testing only.
   */
  Encoder(boolean server, int maxHeaderTableSize, boolean useIndexing, boolean forceHuffmanOn, 
      boolean forceHuffmanOff) {
    this.huffmanEncoder = server ? HpackUtil.RESPONSE_ENCODER : HpackUtil.REQUEST_ENCODER;
    this.useIndexing = useIndexing;
    this.maxHeaderTableSize = maxHeaderTableSize;
    this.forceHuffmanOn = forceHuffmanOn;
    this.forceHuffmanOff = forceHuffmanOff;
  }

  /**
   * Encodes a single header into the header block.
   **/
  public void encodeHeader(OutputStream out, String name, String value) throws IOException {

    int headerSize = HeaderField.sizeOf(name, value);

    // If the headerSize is greater than the max table size then it must be encoded literally
    if (headerSize > maxHeaderTableSize) {
      HeaderEntry nameEntry = getHeaderEntry(name);	
      HeaderEntry entry = getHeaderValue(nameEntry, value);
      int index = headerIndex(entry);
      encodeLiteral(out, name, value, false, index);
      return;
    }

    HeaderEntry nameEntry = getHeaderEntry(name);
    HeaderEntry entry = getHeaderValue(nameEntry, value);

    if (entry != null) {
      if (entry.inReferenceSet) {
        if (!entry.emitted) {
          entry.emitted = true;
        } else {
          // The header was in the reference set and already emitted.

          // remove from reference set, emit, remove from reference set, emit
          for (int i = 0; i < 4; i++) {
            encodeInteger(out, 0x80, 7, headerIndex(entry));
          }

          // inReferenceSet will be set to true after the header block is completed.  In the meantime,
          // inReferenceSet == false && emitted = true represents the state that we've seen this header at least
          // twice in this header block.
          entry.inReferenceSet = false;
        }
      } else {
        if (entry.emitted) {
          // first remove it from the reference set
          encodeInteger(out, 0x80, 7, headerIndex(entry));
        }

        // Section 4.2 - Indexed Header Field
        encodeInteger(out, 0x80, 7, headerIndex(entry));
        entry.emitted = true;
      }
    } else {
      int index = StaticTable.getIndex(name, value);
      if (index != -1) {
        index += size();
        if (useIndexing) {
          ensureCapacity(out, headerSize);
          add(name, value);
        }
  
        // Section 4.2 - Indexed Header Field
        encodeInteger(out, 0x80, 7, index);
      } else {
        int nameIndex = getIndexByName(nameEntry, name);
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
    for (HeaderEntry entry = head; entry != null; entry = entry.next) { 
      if (entry.emitted && !entry.inReferenceSet) {
        entry.inReferenceSet = true;
      }
      if (entry.inReferenceSet && !entry.emitted) {
        encodeInteger(out, 0x80, 7, headerIndex(entry));
        entry.inReferenceSet = false;
      }
      entry.emitted = false;
    }
  }

  private int size() {
    return head == null ? 0 : tail.index - head.index + 1;
  }
  
  public void clearReferenceSet(OutputStream out) throws IOException {
    out.write(0x80);
    
    // encode removed headers
    for (HeaderEntry entry = head; entry != null; entry = entry.next) { 
      entry.inReferenceSet = false;
    }
  }

  public void setHeaderTableSize(OutputStream out, int size) throws IOException {
    maxHeaderTableSize = size;
    ensureCapacity(out, 0);
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

  /**
   * Attempt to lookup the first entry in the header table with a given name.
   *
   * @return  If found, the index, otherwise null.
   **/
  private HeaderEntry getHeaderEntry(String name) {
    return headerByName.get(name);
  }
  
  /**
   * Given the first entry in the header table with a given name, returns the entry with the same name with the given
   * value or null if no such entry exists.
   */
  private HeaderEntry getHeaderValue(HeaderEntry entry, String value) {
    if (entry == null) {
      return null;
    }

    HeaderEntry ret = null;
    while (entry != null) {
      if (HpackUtil.equals(entry.value, value)) {
        // don't short-circuit in order to avoid leaking timing information
        if (ret == null) {
          ret = entry;
        }
      }
      entry = entry.nextName;
    }

    return ret;
  }

  /**
   * Attempt to lookup a value in the header table or the static table by name.
   *
   * @return  If found, the index, otherwise -1.
   **/
  private int getIndexByName(HeaderEntry nameEntry, String name) {
    if (nameEntry != null) {
      return headerIndex(nameEntry);	
    } else {
      int index = StaticTable.getIndex(name);
      if (index >= 0) {
        return index + size();
      } else {
        return -1;
      }
    }
  }

  /**
   * Maps an index in the header table (circular queue) to an actual header index
   */
  private int headerIndex(HeaderEntry entry) {
    return entry.index - head.index + 1;
  }

  /**
   * Adds a new header entry with the given values to the header table.  Evicts the oldest entries in the table to make
   * room, if necessary.
   **/
  private void add(String name, String value) throws IOException {
    HeaderEntry entry = new HeaderEntry(name, value);
    entry.inReferenceSet = false;
    entry.emitted = true;
    
    if (head == null) {
      entry.index = Integer.MAX_VALUE;
      head = tail = entry;
    } else {
      entry.index = head.index - 1;
      entry.next = head;
      head.prev = entry;
      head = entry;
    }

    headerTableSize += HeaderField.sizeOf(name, value);

    // Add a name -> entry mapping
    addHeaderNameMapping(entry);
  }
  
  /**
   * Adds a name -> entry mapping.  If a mapping already exists, the new mapping is
   * prepended to the list of mappings by name.
   **/
  private void addHeaderNameMapping(HeaderEntry entry) {
    entry.nextName = headerByName.remove(entry.name);
    headerByName.put(entry.name, entry);
  }

  /**
   * Ensure that the header table has enough room to hold 'headerSize' more bytes.  Removes the oldest entry from the 
   * header table until sufficient space is available.  If the entry is in the reference set and is marked as 
   * "to-be-emitted", its index is emitted in order to tell the peer to emit the header before it is removed from its                               
   * header table as well.
   **/
  private void ensureCapacity(OutputStream out, int headerSize) throws IOException {
    while (tail != null && (headerTableSize + headerSize > maxHeaderTableSize)) {
      HeaderEntry removed = remove();
      if (removed.inReferenceSet && removed.emitted) {
        // evict the entry from the reference set and emit it
        encodeInteger(out, 0x80, 7, headerIndex(removed));
        encodeInteger(out, 0x80, 7, headerIndex(removed));
      }

      headerTableSize -= HeaderField.sizeOf(removed.name, removed.value);
    }
  }

  /**
   * Removes the oldest entry from the header table.
   **/  
  private HeaderEntry remove() {
    HeaderEntry entry = tail;
    
    tail = tail.prev;
    if (tail != null) {
      tail.next = null;
    }

    removeHeaderNameMapping(entry.name);

    return entry;
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

  private static final class HeaderEntry extends ReferenceHeader {
    
    /**
     * Next entry in insert order
     */
    HeaderEntry next;
    
    /**
     * Previous entry in insert order
     */
    HeaderEntry prev;
    
    /**
     * Next entry in name order.
     */
    HeaderEntry nextName;
    
    /**
     * Used to calculate index of the entry.
     */
    int index;
    
    HeaderEntry(String name, String value) {
      super(name, value);
    }
  }
}
