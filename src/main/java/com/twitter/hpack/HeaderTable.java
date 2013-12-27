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

import java.util.Iterator;

import com.twitter.hpack.HpackUtil.ReferenceHeader;

import static com.twitter.hpack.HpackUtil.HEADER_ENTRY_OVERHEAD;

final class HeaderTable implements Iterable<ReferenceHeader> {

  // a circular queue of reference headers
  private ReferenceHeader[] headerTable;
  private int head;
  private int tail;
  private int size;
  private int capacity;

  HeaderTable(int capacity) {
    setCapacity(capacity);
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
   * Return the header field at the given index.
   * The first and newest entry is always at index 1,
   * and the oldest entry is at the index length().
   */
  public ReferenceHeader get(int index) {
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

  public void add(ReferenceHeader header) {
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

  public int capacity() {
    return capacity;
  }

  public void setCapacity(int capacity) {
    if (capacity < 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    int maxEntries = capacity / HEADER_ENTRY_OVERHEAD;
    if (capacity % HEADER_ENTRY_OVERHEAD != 0) {
      maxEntries++;
    }
    this.capacity = capacity;
    this.headerTable = new ReferenceHeader[maxEntries];
  }

  private void remove() {
    ReferenceHeader removed = headerTable[tail];
    size -= removed.size();
    headerTable[tail++] = null;
    if (tail == headerTable.length) {
      tail = 0;
    }
  }

  public void clear() {
    // null out entries;
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

  public Iterator<ReferenceHeader> iterator() {
    return new Itr();
  }

  private class Itr implements Iterator<ReferenceHeader> {
    int cursor = head;
    int end = tail;

    public boolean hasNext() {
      return cursor != end;
    }

    public ReferenceHeader next() {
      cursor--;
      if (cursor < 0) {
        cursor = headerTable.length - 1;
      }
      return headerTable[cursor];
    }

    public void remove() {
      throw new UnsupportedOperationException();
    }
  }
}
