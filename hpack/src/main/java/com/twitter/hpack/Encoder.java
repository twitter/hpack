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

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import com.twitter.hpack.HpackUtil.IndexType;

public final class Encoder {

  private static final int BUCKET_SIZE = 17;
  private static final byte[] EMPTY = {};

  // for testing
  private final boolean useIndexing;
  private final boolean forceHuffmanOn;
  private final boolean forceHuffmanOff;

  // a linked hash map of header fields
  private final HeaderEntry[] headerFields = new HeaderEntry[BUCKET_SIZE];
  private final HeaderEntry head = new HeaderEntry(-1, EMPTY, EMPTY, Integer.MAX_VALUE, null);
  private int size;
  private int capacity;

  /**
   * Creates a new encoder.
   */
  public Encoder(final int maxHeaderTableSize) {
    this(maxHeaderTableSize, true, false, false);
  }

  /**
   * Constructor for testing only.
   */
  Encoder(
          final int maxHeaderTableSize,
          final boolean useIndexing,
          final boolean forceHuffmanOn,
          final boolean forceHuffmanOff
  ) {
    if (maxHeaderTableSize < 0) {
      throw new IllegalArgumentException("Illegal Capacity: " + maxHeaderTableSize);
    }
    this.useIndexing = useIndexing;
    this.forceHuffmanOn = forceHuffmanOn;
    this.forceHuffmanOff = forceHuffmanOff;
    this.capacity = maxHeaderTableSize;
    head.before = head.after = head;
  }

  /**
   * Encode the header field into the header block.
   */
  public void encodeHeader(final OutputStream out, final byte[] name, final byte[] value, final boolean sensitive) throws IOException {

    // If the header value is sensitive then it must never be indexed
    if (sensitive) {
      final int nameIndex = getNameIndex(name);
      encodeLiteral(out, name, value, IndexType.NEVER, nameIndex);
      return;
    }

    // If the peer will only use the static table
    if (capacity == 0) {
      final int staticTableIndex = StaticTable.getIndex(name, value);
      if (staticTableIndex == -1) {
        final int nameIndex = StaticTable.getIndex(name);
        encodeLiteral(out, name, value, IndexType.NONE, nameIndex);
      } else {
        encodeInteger(out, 0x80, 7, staticTableIndex);
      }
      return;
    }

    final int headerSize = HeaderField.sizeOf(name, value);

    // If the headerSize is greater than the max table size then it must be encoded literally
    if (headerSize > capacity) {
      final int nameIndex = getNameIndex(name);
      encodeLiteral(out, name, value, IndexType.NONE, nameIndex);
      return;
    }

    final HeaderEntry headerField = getEntry(name, value);
    if (headerField != null) {
      final int index = getIndex(headerField.index) + StaticTable.length;
      // Section 6.1. Indexed Header Field Representation
      encodeInteger(out, 0x80, 7, index);
    } else {
      final int staticTableIndex = StaticTable.getIndex(name, value);
      if (staticTableIndex != -1) {
        // Section 6.1. Indexed Header Field Representation
        encodeInteger(out, 0x80, 7, staticTableIndex);
      } else {
        final int nameIndex = getNameIndex(name);
        if (useIndexing) {
          ensureCapacity(headerSize);
        }
        final IndexType indexType = useIndexing ? IndexType.INCREMENTAL : IndexType.NONE;
        encodeLiteral(out, name, value, indexType, nameIndex);
        if (useIndexing) {
          add(name, value);
        }
      }
    }
  }

  /**
   * Set the maximum table size.
   */
  public void setMaxHeaderTableSize(final OutputStream out, final int maxHeaderTableSize) throws IOException {
    if (maxHeaderTableSize < 0) {
      throw new IllegalArgumentException("Illegal Capacity: " + maxHeaderTableSize);
    }
    if (capacity == maxHeaderTableSize) {
      return;
    }
    capacity = maxHeaderTableSize;
    ensureCapacity(0);
    encodeInteger(out, 0x20, 5, maxHeaderTableSize);
  }

  /**
   * Return the maximum table size.
   */
  public int getMaxHeaderTableSize() {
    return capacity;
  }

  /**
   * Encode integer according to Section 5.1.
   */
  private static void encodeInteger(final OutputStream out, final int mask, final int n, final int i) throws IOException {
    if (n < 0 || n > 8) {
      throw new IllegalArgumentException("N: " + n);
    }
    final int nbits = 0xFF >>> (8 - n);
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
   * Encode string literal according to Section 5.2.
   */
  private void encodeStringLiteral(final OutputStream out, final byte[] string) throws IOException {
    final int huffmanLength = Huffman.ENCODER.getEncodedLength(string);
    if ((huffmanLength < string.length && !forceHuffmanOff) || forceHuffmanOn) {
      encodeInteger(out, 0x80, 7, huffmanLength);
      Huffman.ENCODER.encode(out, string);
    } else {
      encodeInteger(out, 0x00, 7, string.length);
      out.write(string, 0, string.length);
    }
  }

  /**
   * Encode literal header field according to Section 6.2.
   */
  private void encodeLiteral(final OutputStream out, final byte[] name, final byte[] value, final IndexType indexType, final int nameIndex)
      throws IOException {
    final int mask;
    final int prefixBits;
    switch(indexType) {
    case INCREMENTAL:
      mask = 0x40;
      prefixBits = 6;
      break;
    case NONE:
      mask = 0x00;
      prefixBits = 4;
      break;
    case NEVER:
      mask = 0x10;
      prefixBits = 4;
      break;
    default:
      throw new IllegalStateException("should not reach here");
    }
    encodeInteger(out, mask, prefixBits, nameIndex == -1 ? 0 : nameIndex);
    if (nameIndex == -1) {
      encodeStringLiteral(out, name);
    }
    encodeStringLiteral(out, value);
  }

  private int getNameIndex(final byte[] name) {
    int index = StaticTable.getIndex(name);
    if (index == -1) {
      index = getIndex(name);
      if (index >= 0) {
        index += StaticTable.length;
      }
    }
    return index;
  }

  /**
   * Ensure that the dynamic table has enough room to hold 'headerSize' more bytes.
   * Removes the oldest entry from the dynamic table until sufficient space is available.
   */
  private void ensureCapacity(final int headerSize) {
    while (size + headerSize > capacity) {
      final int index = length();
      if (index == 0) {
        break;
      }
      remove();
    }
  }

  /**
   * Return the number of header fields in the dynamic table.
   * Exposed for testing.
   */
  int length() {
    return size == 0 ? 0 : head.after.index - head.before.index + 1;
  }

  /**
   * Return the size of the dynamic table.
   * Exposed for testing.
   */
  int size() {
    return size;
  }

  /**
   * Return the header field at the given index.
   * Exposed for testing.
   */
  HeaderField getHeaderField(int index) {
    HeaderEntry entry = head;
    while(index-- >= 0) {
      entry = entry.before;
    }
    return entry;
  }

  /**
   * Returns the header entry with the lowest index value for the header field.
   * Returns null if header field is not in the dynamic table.
   */
  private HeaderEntry getEntry(final byte[] name, final byte[] value) {
    if (length() == 0 || name == null || value == null) {
      return null;
    }
    final int h = hash(name);
    final int i = index(h);
    for (HeaderEntry e = headerFields[i]; e != null; e = e.next) {
      if (e.hash == h &&
          HpackUtil.equals(name, e.name) &&
          HpackUtil.equals(value, e.value)) {
        return e;
      }
    }
    return null;
  }

  /**
   * Returns the lowest index value for the header field name in the dynamic table.
   * Returns -1 if the header field name is not in the dynamic table.
   */
  private int getIndex(final byte[] name) {
    if (length() == 0 || name == null) {
      return -1;
    }
    final int h = hash(name);
    final int i = index(h);
    int index = -1;
    for (HeaderEntry e = headerFields[i]; e != null; e = e.next) {
      if (e.hash == h && HpackUtil.equals(name, e.name)) {
        index = e.index;
        break;
      }
    }
    return getIndex(index);
  }

  /**
   * Compute the index into the dynamic table given the index in the header entry.
   */
  private int getIndex(final int index) {
    if (index == -1) {
      return index;
    }
    return index - head.before.index + 1;
  }

  /**
   * Add the header field to the dynamic table.
   * Entries are evicted from the dynamic table until the size of the table
   * and the new header field is less than the table's capacity.
   * If the size of the new entry is larger than the table's capacity,
   * the dynamic table will be cleared.
   */
  private void add(byte[] name, byte[] value) {
    final int headerSize = HeaderField.sizeOf(name, value);

    // Clear the table if the header field size is larger than the capacity.
    if (headerSize > capacity) {
      clear();
      return;
    }

    // Evict oldest entries until we have enough capacity.
    while (size + headerSize > capacity) {
      remove();
    }

    // Copy name and value that modifications of original do not affect the dynamic table.
    name = Arrays.copyOf(name, name.length);
    value = Arrays.copyOf(value, value.length);

    final int h = hash(name);
    final int i = index(h);
    final HeaderEntry old = headerFields[i];
    final HeaderEntry e = new HeaderEntry(h, name, value, head.before.index - 1, old);
    headerFields[i] = e;
    e.addBefore(head);
    size += headerSize;
  }

  /**
   * Remove and return the oldest header field from the dynamic table.
   */
  private HeaderField remove() {
    if (size == 0) {
      return null;
    }
    final HeaderEntry eldest = head.after;
    final int h = eldest.hash;
    final int i = index(h);
    HeaderEntry prev = headerFields[i];
    HeaderEntry e = prev;
    while (e != null) {
      final HeaderEntry next = e.next;
      if (e == eldest) {
        if (prev == eldest) {
          headerFields[i] = next;
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
   * Remove all entries from the dynamic table.
   */
  private void clear() {
    Arrays.fill(headerFields, null);
    head.before = head.after = head;
    this.size = 0;
  }

  /**
   * Returns the hash code for the given header field name.
   */
  private static int hash(final byte[] name) {
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
  private static int index(final int h) {
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
    final int hash;

    // This is used to compute the index in the dynamic table.
    final int index;

    /**
     * Creates new entry.
     */
    HeaderEntry(final int hash, final byte[] name, final byte[] value, final int index, final HeaderEntry next) {
      super(name, value);
      this.index = index;
      this.hash = hash;
      this.next = next;
    }

    /**
     * Removes this entry from the linked list.
     */
    private void remove() {
      before.after = after;
      after.before = before;
      before = null; // null reference to prevent nepotism with generational GC.
      after = null;  // null reference to prevent nepotism with generational GC.
      next = null;   // null reference to prevent nepotism with generational GC.
    }

    /**
     * Inserts this entry before the specified existing entry in the list.
     */
    private void addBefore(final HeaderEntry existingEntry) {
      after  = existingEntry;
      before = existingEntry.before;
      before.after = this;
      after.before = this;
    }
  }
}
