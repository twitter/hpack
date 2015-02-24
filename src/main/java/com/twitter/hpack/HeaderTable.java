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

import static com.twitter.hpack.HeaderField.HEADER_ENTRY_OVERHEAD;

class HeaderTable {

  // a circular queue of header fields
  HeaderField[] headerTable;
  int head;
  int tail;
  private int size;
  private int capacity = -1; // ensure setCapacity creates headerTable

  HeaderTable(int initialCapacity) {
    setCapacity(initialCapacity);
  }

  /**
   * Return the number of header fields in the header table.
   */
  public int length() {
    int length;
    if (head < tail) {
      length = headerTable.length - tail + head;
    } else {
      length = head - tail;
    }
    return length;
  }

  /**
   * Return the current size of the header table.
   * This is the sum of the size of the entries.
   */
  public int size() {
    return size;
  }

  /**
   * Return the maximum allowable size of the header table.
   */
  public int capacity() {
    return capacity;
  }

  /**
   * Return the header field at the given index.
   * The first and newest entry is always at index 1,
   * and the oldest entry is at the index length().
   */
  public HeaderField getEntry(int index) {
    if (index <= 0 || index > length()) {
      throw new IndexOutOfBoundsException();
    }
    int i = head - index;
    if (i < 0) {
      return headerTable[i + headerTable.length];
    } else {
      return headerTable[i];
    }
  }

  /**
   * Add the header field to the header table.
   * Entries are evicted from the header table until the size of the table
   * and the new header field is less than the table's capacity.
   * If the size of the new entry is larger than the table's capacity,
   * the header table will be cleared.
   */
  public void add(HeaderField header) {
    int headerSize = header.size();
    if (headerSize > capacity) {
      clear();
      return;
    }
    while (size + headerSize > capacity) {
      remove();
    }
    headerTable[head++] = header;
    size += header.size();
    if (head == headerTable.length) {
      head = 0;
    }
  }


  /**
   * Remove and return the oldest header field from the header table.
   */
  public HeaderField remove() {
    HeaderField removed = headerTable[tail];
    if (removed == null) {
      return null;
    }
    size -= removed.size();
    headerTable[tail++] = null;
    if (tail == headerTable.length) {
      tail = 0;
    }
    return removed;
  }

  /**
   * Remove all entries from the header table.
   */
  public void clear() {
    while (tail != head) {
      headerTable[tail++] = null;
      if (tail == headerTable.length) {
        tail = 0;
      }
    }
    head = 0;
    tail = 0;
    size = 0;
  }

  public void setCapacity(int capacity) {
    if (capacity < 0) {
      throw new IllegalArgumentException("Illegal Capacity: "+ capacity);
    }

    // initially capacity will be -1 so init won't return here
    if (this.capacity == capacity) {
      return;
    }
    this.capacity = capacity;

    // initially size will be 0 so remove won't be called
    if (capacity == 0) {
      clear();
    } else {
      while (size > capacity) {
        remove();
      }
    }

    int maxEntries = capacity / HEADER_ENTRY_OVERHEAD;
    if (capacity % HEADER_ENTRY_OVERHEAD != 0) {
      maxEntries++;
    }

    // check if capacity change requires us to reallocate the headerTable
    if (headerTable != null && headerTable.length == maxEntries) {
      return;
    }

    HeaderField[] tmp = new HeaderField[maxEntries];

    // initially length will be 0 so there will be no copy
    int len = length();
    int cursor = tail;
    for (int i = 0; i < len; i++) {
      HeaderField entry = headerTable[cursor++];
      tmp[i] = entry;
      if (cursor == headerTable.length) {
        cursor = 0;
      }
    }

    this.tail = 0;
    this.head = tail + len;
    this.headerTable = tmp;
  }
}
