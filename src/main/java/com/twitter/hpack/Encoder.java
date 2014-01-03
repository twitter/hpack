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
import java.util.Arrays;

public final class Encoder {

  private static final int BUCKET_SIZE = 17;
  private static final byte[] EMPTY = {};

  // For testing
  private final boolean useIndexing;
  private final boolean forceHuffmanOn;
  private final boolean forceHuffmanOff;

  // the huffman encoder used to encode literal values
  private final HuffmanEncoder huffmanEncoder;

  // a linked hash map of header fields
  private final HeaderEntry[] headerTable = new HeaderEntry[BUCKET_SIZE];
  private final HeaderEntry head = new HeaderEntry(-1, EMPTY, EMPTY, Integer.MAX_VALUE, null);
  private int size;
  private int capacity;

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
    if (maxHeaderTableSize < 0) {
      throw new IllegalArgumentException("Illegal Capacity: "+ maxHeaderTableSize);
    }
    this.huffmanEncoder = server ? HpackUtil.RESPONSE_ENCODER : HpackUtil.REQUEST_ENCODER;
    this.useIndexing = useIndexing;
    this.forceHuffmanOn = forceHuffmanOn;
    this.forceHuffmanOff = forceHuffmanOff;
    this.capacity = maxHeaderTableSize;
    head.before = head.after = head;
  }

  /**
   * Encode the header field into the header block.
   */
  public void encodeHeader(OutputStream out, byte[] name, byte[] value) throws IOException {

    int headerSize = HeaderField.sizeOf(name, value);

    // If the headerSize is greater than the max table size then it must be encoded literally
    if (headerSize > capacity) {
      int nameIndex = getNameIndex(name);
      encodeLiteral(out, name, value, false, nameIndex);
      return;
    }

    HeaderEntry headerField = getEntry(name, value);
    if (headerField != null) {
      int index = getIndex(headerField.index);
      if (headerField.inReferenceSet) {
        if (headerField.emitted) {
          // This header field will be emitted by the decoder once the header block is complete.
          // Since we have seen this header field before we must emit it twice.
          encodeInteger(out, 0x80, 7, index); // remove from reference set
          encodeInteger(out, 0x80, 7, index); // insert into reference set and emit
          encodeInteger(out, 0x80, 7, index); // remove from reference set
          encodeInteger(out, 0x80, 7, index); // insert into reference set and emit

          // We indicate that that the decoder will not emit this header again after the
          // header block is complete by setting inReferenceSet to false. It will be set
          // to true again by endHeaders after the header block is complete.
          headerField.inReferenceSet = false;
        } else {
          // Mark this entry as "to-be-emitted" indicating that it is in the decoder's reference
          // set and should be emitted by the decoder after the header block is complete.
          headerField.emitted = true;
        }
      } else {
        if (headerField.emitted) {
          // This header field has already been emitted by the decoder so we must remove it
          // from the reference set before we can emit it again.
          encodeInteger(out, 0x80, 7, index);
        }

        // Section 4.2 - Indexed Header Field
        encodeInteger(out, 0x80, 7, index);
        headerField.emitted = true;
      }
    } else {
      int staticTableIndex = StaticTable.getIndex(name, value);
      if (staticTableIndex != -1 && useIndexing) {
        // Section 4.2 - Indexed Header Field
        int nameIndex = staticTableIndex + length();
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
   * End the current header set. This must be called after all header fields
   * in the current header set have been encoded.
   */
  public void endHeaders(OutputStream out) throws IOException {
    HeaderEntry entry = head.before;
    while (entry != head) {
      if (entry.emitted) {
        // The header field either has been emitted or will be emitted by the decoder.
        entry.inReferenceSet = true;
        entry.emitted = false;
      } else if (entry.inReferenceSet) {
        // The header field is not part of this header set and must be removed from the reference set.
        encodeInteger(out, 0x80, 7, getIndex(entry.index));
        entry.inReferenceSet = false;
      }
      entry = entry.before;
    }
  }

  /**
   * Clear the current reference set.
   */
  public void clearReferenceSet(OutputStream out) throws IOException {
    HeaderEntry entry = head.before;
    while (entry != head) {
      entry.inReferenceSet = false;
      entry.emitted = false;
      entry = entry.before;
    }
    out.write(0x80);
  }

  public void setHeaderTableSize(OutputStream out, int maxHeaderTableSize) throws IOException {
    if (maxHeaderTableSize < 0) {
      throw new IllegalArgumentException("Illegal Capacity: "+ maxHeaderTableSize);
    }
    int neededSize = size - maxHeaderTableSize;
    if (neededSize > 0) {
      ensureCapacity(out, neededSize);
    }
    this.capacity = maxHeaderTableSize;
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
  private void encodeLiteral(OutputStream out, byte[] name, byte[] value, boolean indexing, int nameIndex)
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
  private void encodeStringLiteral(OutputStream out, byte[] string) throws IOException {
    int huffmanLength = huffmanEncoder.getEncodedLength(string);

    if ((huffmanLength < string.length && !forceHuffmanOff) || forceHuffmanOn) {
      encodeInteger(out, 0x80, 7, huffmanLength);
      huffmanEncoder.encode(string, out);
    } else {
      encodeInteger(out, 0x00, 7, string.length);
      out.write(string, 0, string.length);
    }
  }

  private int getNameIndex(byte[] name) {
    int index = getIndex(name);
    if (index == -1) {
      index = StaticTable.getIndex(name);
      if (index >= 0) {
        index += length();
      }
    }
    return index;
  }

  /**
   * Ensure that the header table has enough room to hold 'headerSize' more bytes.
   * Removes the oldest entry from the header table until sufficient space is available.
   * If the entry is in the reference set and is marked as "to-be-emitted", its index is
   * emitted in order to tell the peer to emit the header before it is removed from its
   * header table as well.
   */
  private void ensureCapacity(OutputStream out, int headerSize) throws IOException {
    while (size + headerSize > capacity) {
      int index = length();
      if (index == 0) {
        break;
      }
      HeaderField removed = remove();
      if (removed.inReferenceSet && removed.emitted) {
        // evict the entry from the reference set and emit it
        encodeInteger(out, 0x80, 7, index);
        encodeInteger(out, 0x80, 7, index);
      }
    }
  }

  /**
   * Return the number of header fields in the header table.
   */
  private int length() {
    return size == 0 ? 0 : head.after.index - head.before.index + 1;
  }

  /**
   * Returns the header entry with the lowest index value for the header field.
   * Returns null if header field is not in the header table.
   */
  private HeaderEntry getEntry(byte[] name, byte[] value) {
    if (length() == 0 || name == null || value == null) {
      return null;
    }
    int h = hash(name);
    int i = index(h);
    for (HeaderEntry e = headerTable[i]; e != null; e = e.next) {
      if (e.hash == h &&
          HpackUtil.equals(name, e.name) &&
          HpackUtil.equals(value, e.value)) {
        return e;
      }
    }
    return null;
  }

  /**
   * Returns the lowest index value for the header field name in the header table.
   * Returns -1 if the header field name is not in the header table.
   */
  private int getIndex(byte[] name) {
    if (length() == 0 || name == null) {
      return -1;
    }
    int h = hash(name);
    int i = index(h);
    int index = -1;
    for (HeaderEntry e = headerTable[i]; e != null; e = e.next) {
      if (e.hash == h && HpackUtil.equals(name, e.name)) {
        index = e.index;
        break;
      }
    }
    return getIndex(index);
  }

  /**
   * Compute the index into the header table given the index in the header entry.
   */
  private int getIndex(int index) {
    if (index == -1) {
      return index;
    }
    return index - head.before.index + 1;
  }

  /**
   * Add the header field to the header table.
   * Entries are evicted from the header table until the size of the table
   * and the new header field is less than the table's capacity.
   * If the size of the new entry is larger than the table's capacity,
   * the header table will be cleared.
   */
  private void add(byte[] name, byte[] value) {
    int headerSize = HeaderField.sizeOf(name, value);

    // Clear the table if the header field size is larger than the capacity.
    if (headerSize > capacity) {
      clear();
      return;
    }

    // Evict oldest entries until we have enough capacity.
    while (size + headerSize > capacity) {
      remove();
    }

    // Copy name and value that modifications of original do not affect the header table.
    name = Arrays.copyOf(name, name.length);
    value = Arrays.copyOf(value, value.length);

    int h = hash(name);
    int i = index(h);
    HeaderEntry old = headerTable[i];
    HeaderEntry e = new HeaderEntry(h, name, value, head.before.index - 1, old);
    headerTable[i] = e;
    e.addBefore(head);
    size += headerSize;
  }

  /**
   * Remove and return the oldest header field from the header table.
   */
  private HeaderField remove() {
    if (size == 0) {
      return null;
    }
    HeaderEntry eldest = head.after;
    int h = eldest.hash;
    int i = index(h);
    HeaderEntry prev = headerTable[i];
    HeaderEntry e = prev;
    while (e != null) {
      HeaderEntry next = e.next;
      if (e == eldest) {
        if (prev == eldest) {
          headerTable[i] = next;
        } else {
          prev.next = next;
        }
        eldest.remove();
        size -= eldest.size();
        return eldest;
      }
      prev = e;
      e = next;
    }
    return null;
  }

  /**
   * Remove all entries from the header table.
   */
  private void clear() {
    Arrays.fill(headerTable, null);
    head.before = head.after = head;
    this.size = 0;
  }

  /**
   * Returns the hash code for the given header field name.
   */
  private static int hash(byte[] name) {
    int h = 0;
    for (int i = 0; i < name.length; i++) {
      h = 31 * h + name[i];
    }
    if (h > 0) {
      return h;
    } else if (h == Integer.MIN_VALUE) {
      return Integer.MAX_VALUE;
    } else {
      return -h;
    }
  }

  /**
   * Returns the index into the hash table for the hash code h.
   */
  private static int index(int h) {
    return h % BUCKET_SIZE;
  }

  /**
   * A linked hash map HeaderField entry.
   */
  private static class HeaderEntry extends HeaderField {
    // These fields comprise the doubly linked list used for iteration.
    HeaderEntry before, after;

    // These fields comprise the chained list for header fields with the same hash.
    HeaderEntry next;
    int hash;

    // This is used to compute the index in the header table.
    int index;

    /**
     * Creates new entry.
     */
    HeaderEntry(int hash, byte[] name, byte[] value, int index, HeaderEntry next) {
      super(name, value);
      this.index = index;
      this.hash = hash;
      this.next = next;
      this.emitted = true;
    }

    /**
     * Removes this entry from the linked list.
     */
    private void remove() {
      before.after = after;
      after.before = before;
    }

    /**
     * Inserts this entry before the specified existing entry in the list.
     */
    private void addBefore(HeaderEntry existingEntry) {
      after  = existingEntry;
      before = existingEntry.before;
      before.after = this;
      after.before = this;
    }
  }
}
