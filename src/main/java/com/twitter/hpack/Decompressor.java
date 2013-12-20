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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.twitter.hpack.HpackUtil.ReferenceHeader;

/**
 * <a href="http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-03">
 * HPACK: HTTP/2.0 Header Compression</a>
 */
public class Decompressor {

  private static final IOException DECOMPRESSION_EXCEPTION = new IOException("decompression failure");

  private final List<ReferenceHeader> headerTable;
  private int headerTableSize;

  private final int maxHeaderSize;
  private long headerSize;

  private State state;
  private IndexType indexType;
  private int index;
  private int skipLength;
  private int nameLength;
  private int valueLength;
  private String name;
  private String value;

  private enum State {
    READ_HEADER_REPRESENTATION,
    READ_INDEXED_HEADER,
    READ_INDEXED_HEADER_NAME,
    READ_LITERAL_HEADER_NAME_LENGTH,
    READ_LITERAL_HEADER_NAME,
    SKIP_LITERAL_HEADER_NAME,
    READ_SUBSTITUTED_INDEX,
    READ_LITERAL_HEADER_VALUE_LENGTH,
    READ_LITERAL_HEADER_VALUE,
    SKIP_LITERAL_HEADER_VALUE
  }

  private enum IndexType {
    NONE,
    INCREMENTAL,
    SUBSTITUTION
  }

  public Decompressor(boolean server, int maxHeaderSize) {
    if (server) {
      headerTable = HpackUtil.newRequestTable();
      headerTableSize = HpackUtil.REQUEST_TABLE_SIZE;
    } else {
      headerTable = HpackUtil.newResponseTable();
      headerTableSize = HpackUtil.RESPONSE_TABLE_SIZE;
    }
    this.maxHeaderSize = maxHeaderSize;
    reset();
  }

  private void reset() {
    headerSize = 0;
    state = State.READ_HEADER_REPRESENTATION;
    indexType = IndexType.NONE;
  }

  public void decode(InputStream in, HeaderListener headerListener) throws IOException {
    while (in.available() > 0) {
      switch(state) {
      case READ_HEADER_REPRESENTATION:
        byte b = (byte) in.read();
        if (b < 0) {
          // Indexed Header Representation
          index = b & 0x7F;
          if (index == 0x7F) {
            state = State.READ_INDEXED_HEADER;
          } else {
            toggleIndex(index, headerListener);
          }

        } else if ((b & 0x40) == 0) {
          // Literal Header with Substitution Indexing
          indexType = IndexType.SUBSTITUTION;
          index = b & 0x3F;
          if (index == 0) {
            state = State.READ_LITERAL_HEADER_NAME_LENGTH;
          } else if (index == 0x3F) {
            // Index + 1 was stored as the prefix
            index--;
            state = State.READ_INDEXED_HEADER_NAME;
          } else {
            // Index + 1 was stored as the prefix
            readName(index - 1);
            state = State.READ_SUBSTITUTED_INDEX;
          }

        } else if ((b & 0x20) == 0) {
          // Literal Header with Incremental Indexing
          indexType = IndexType.INCREMENTAL;
          index = b & 0x1F;
          if (index == 0) {
            state = State.READ_LITERAL_HEADER_NAME_LENGTH;
          } else if (index == 0x1F) {
            // Index + 1 was stored as the prefix
            index--;
            state = State.READ_INDEXED_HEADER_NAME;
          } else {
            // Index + 1 was stored as the prefix
            readName(index - 1);
            state = State.READ_LITERAL_HEADER_VALUE_LENGTH;
          }

        } else {
          // Literal Header without Indexing
          indexType = IndexType.NONE;
          index = b & 0x1F;
          if (index == 0) {
            state = State.READ_LITERAL_HEADER_NAME_LENGTH;
          } else if (index == 0x1F) {
            // Index + 1 was stored as the prefix
            index--;
            state = State.READ_INDEXED_HEADER_NAME;
          } else {
            // Index + 1 was stored as the prefix
            readName(index - 1);
            state = State.READ_LITERAL_HEADER_VALUE_LENGTH;
          }
        }
        break;

      case READ_INDEXED_HEADER:
        int headerIndex = decodeULE128(in);
        if (headerIndex == -1) {
          return;
        }

        // Check for numerical overflow
        if (headerIndex > Integer.MAX_VALUE - index) {
          throw DECOMPRESSION_EXCEPTION;
        }

        toggleIndex(index + headerIndex, headerListener);
        state = State.READ_HEADER_REPRESENTATION;
        break;

      case READ_INDEXED_HEADER_NAME:
        // Header Name matches an entry in the Header Table
        int nameIndex = decodeULE128(in);
        if (nameIndex == -1) {
          return;
        }

        // Check for numerical overflow
        if (nameIndex > Integer.MAX_VALUE - index) {
          throw DECOMPRESSION_EXCEPTION;
        }

        readName(index + nameIndex);
        if (indexType == IndexType.SUBSTITUTION) {
          state = State.READ_SUBSTITUTED_INDEX;
        } else {
          state = State.READ_LITERAL_HEADER_VALUE_LENGTH;
        }
        break;

      case READ_LITERAL_HEADER_NAME_LENGTH:
        // Header Name is a Literal String
        nameLength = decodeULE128(in);
        if (nameLength == -1) {
          return;
        }

        // Disallow empty names -- they cannot be represented in HTTP/1.x
        if (nameLength == 0) {
          throw DECOMPRESSION_EXCEPTION;
        }

        // Check name length against max header size
        if (nameLength + headerSize > maxHeaderSize) {
          // truncation will be reported during endHeaderBlock
          headerSize = maxHeaderSize + 1;

          if (indexType == IndexType.NONE) {
            // Name is unused so skip bytes
            name = HpackUtil.EMPTY;
            skipLength = nameLength;
            state = State.SKIP_LITERAL_HEADER_NAME;
            break;
          }

          // Check name length against max header table size
          if (nameLength + ReferenceHeader.HEADER_ENTRY_OVERHEAD > HpackUtil.MAX_HEADER_TABLE_SIZE) {
            headerTable.clear();
            headerTableSize = 0;
            name = HpackUtil.EMPTY;
            skipLength = nameLength;
            state = State.SKIP_LITERAL_HEADER_NAME;
            break;
          }
        }
        state = State.READ_LITERAL_HEADER_NAME;
        break;

      case READ_LITERAL_HEADER_NAME:
        // Wait until entire name is readable
        if (in.available() < nameLength) {
          return;
        }

        byte[] nameBytes = new byte[nameLength];
        in.read(nameBytes);
        name = new String(nameBytes, StandardCharsets.UTF_8);

        if (indexType == IndexType.SUBSTITUTION) {
          state = State.READ_SUBSTITUTED_INDEX;
        } else {
          state = State.READ_LITERAL_HEADER_VALUE_LENGTH;
        }
        break;

      case SKIP_LITERAL_HEADER_NAME:
        skipLength -= in.skip(skipLength);

        if (skipLength == 0) {
          if (indexType == IndexType.SUBSTITUTION) {
            state = State.READ_SUBSTITUTED_INDEX;
          } else {
            state = State.READ_LITERAL_HEADER_VALUE_LENGTH;
          }
        }
        break;

      case READ_SUBSTITUTED_INDEX:
        // Substituted Index
        index = decodeULE128(in);
        if (index == -1) {
          return;
        }

        if (index >= headerTable.size()) {
          throw DECOMPRESSION_EXCEPTION;
        }

        state = State.READ_LITERAL_HEADER_VALUE_LENGTH;
        break;

      case READ_LITERAL_HEADER_VALUE_LENGTH:
        // Header Value is a Literal String
        valueLength = decodeULE128(in);
        if (valueLength == -1) {
          return;
        }

        if (valueLength == 0) {
          value = HpackUtil.EMPTY;
          insertHeader(headerListener);
          state = State.READ_HEADER_REPRESENTATION;
          break;
        }

        // Check new header size against max header size
        long newHeaderSize = (long) nameLength + (long) valueLength;
        if (newHeaderSize + headerSize > maxHeaderSize) {
          // truncation will be reported during endHeaderBlock
          headerSize = maxHeaderSize + 1;

          if (indexType == IndexType.NONE) {
            // Value is unused so skip bytes
            state = State.SKIP_LITERAL_HEADER_VALUE;
            break;
          }

          // Check new header size against max header table size
          if (newHeaderSize + ReferenceHeader.HEADER_ENTRY_OVERHEAD > HpackUtil.MAX_HEADER_TABLE_SIZE) {
            headerTable.clear();
            headerTableSize = 0;
            state = State.SKIP_LITERAL_HEADER_VALUE;
            break;
          }
        }
        state = State.READ_LITERAL_HEADER_VALUE;
        break;

      case READ_LITERAL_HEADER_VALUE:
        // Wait until entire value is readable
        if (in.available() < valueLength) {
          return;
        }

        byte[] valueBytes = new byte[valueLength];
        in.read(valueBytes);
        value = new String(valueBytes, StandardCharsets.UTF_8);
        insertHeader(headerListener);
        state = State.READ_HEADER_REPRESENTATION;
        break;

      case SKIP_LITERAL_HEADER_VALUE:
        valueLength -= in.skip(valueLength);

        if (valueLength == 0) {
          state = State.READ_HEADER_REPRESENTATION;
        }
        break;

      default:
        throw new IllegalStateException("should not reach here");
      }
    }
  }

  public boolean endHeaderBlock(HeaderListener headerListener) {
    for (ReferenceHeader referenceHeader : headerTable) {
      if (referenceHeader.inReferenceSet && !referenceHeader.emitted) {
        emitHeader(headerListener, referenceHeader.name, referenceHeader.value);
      }
      referenceHeader.emitted = false;
    }
    boolean truncated = headerSize > maxHeaderSize;
    reset();
    return truncated;
  }

  private void readName(int index) throws IOException {
    if (index >= headerTable.size()) {
      throw DECOMPRESSION_EXCEPTION;
    }
    ReferenceHeader referenceHeader = headerTable.get(index);
    name = referenceHeader.name;
    nameLength =referenceHeader.nameLength;
  }

  private void toggleIndex(int index, HeaderListener headerListener) throws IOException {
    if (index >= headerTable.size()) {
      throw DECOMPRESSION_EXCEPTION;
    }

    ReferenceHeader referenceHeader = headerTable.get(index);
    if (referenceHeader.inReferenceSet) {
      referenceHeader.inReferenceSet = false;
    } else {
      referenceHeader.inReferenceSet = true;
      referenceHeader.emitted = true;
      emitHeader(headerListener, referenceHeader.name, referenceHeader.value);
    }
  }

  private void insertHeader(HeaderListener headerListener) {
    emitHeader(headerListener, name, value);

    switch (indexType) {
      case NONE:
        break;

      case INCREMENTAL:
        ReferenceHeader referenceHeader = new ReferenceHeader(name, value, nameLength, valueLength);
        referenceHeader.emitted = true;
        referenceHeader.inReferenceSet = true;
        int headerSize = referenceHeader.size();
        if (headerSize > HpackUtil.MAX_HEADER_TABLE_SIZE) {
          headerTable.clear();
          headerTableSize = 0;
          break;
        }
        while (headerTableSize + headerSize > HpackUtil.MAX_HEADER_TABLE_SIZE) {
          ReferenceHeader removedHeader = headerTable.remove(0);
          headerTableSize -= removedHeader.size();
        }
        headerTable.add(referenceHeader);
        headerTableSize += headerSize;
        break;

      case SUBSTITUTION:
        // When the modification of the header table is the replacement of an
        // existing entry, the replaced entry is the one indicated in the
        // literal representation before any entry is removed from the header
        // table.  If the entry to be replaced is removed from the header table
        // when performing the size adjustment, the replacement entry is
        // inserted at the beginning of the header table.
        ReferenceHeader replacedHeader = headerTable.get(index);
        int oldHeaderSize = replacedHeader.size();
        ReferenceHeader substitutedHeader = new ReferenceHeader(name, value, nameLength, valueLength);
        substitutedHeader.emitted = true;
        substitutedHeader.inReferenceSet = true;
        int newHeaderSize = substitutedHeader.size();
        if (newHeaderSize > HpackUtil.MAX_HEADER_TABLE_SIZE) {
          headerTable.clear();
          headerTableSize = 0;
          break;
        }
        while (headerTableSize + newHeaderSize - oldHeaderSize > HpackUtil.MAX_HEADER_TABLE_SIZE) {
          ReferenceHeader removedHeader = headerTable.remove(0);
          headerTableSize -= removedHeader.size();
          if (index == 0) {
            oldHeaderSize = 0;
          }
          if (index >= 0) {
            index--;
          }
        }
        if (index < 0) {
          headerTable.add(0, substitutedHeader);
        } else {
          headerTable.set(index, substitutedHeader);
        }
        break;

      default:
        throw new IllegalStateException("should not reach here");
    }
  }

  private void emitHeader(HeaderListener headerListener, String name, String value) {
    if (name.length() == 0) {
      throw new AssertionError("name is empty");
    }
    long newSize = headerSize + name.length() + value.length();
    if (newSize <= maxHeaderSize) {
      headerListener.emitHeader(name, value);
      headerSize = (int) newSize;
    } else {
      // truncation will be reported during endHeaderBlock
      headerSize = maxHeaderSize + 1;
    }
  }

  // Unsigned Little Endian Base 128 Variable-Length Integer Encoding
  private static int decodeULE128(InputStream in) throws IOException {
    in.mark(5);
    int result = 0;
    int shift = 0;
    while (shift < 32) {
      if (in.available() == 0) {
        // Buffer does not contain entire integer,
        // reset reader index and return -1.
        in.reset();
        return -1;
      }
      byte b = (byte) in.read();
      if (shift == 28 && (b & 0xF8) != 0) {
        break;
      }
      result |= (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
    // Value exceeds Integer.MAX_VALUE
    in.reset();
    throw DECOMPRESSION_EXCEPTION;
  }
}
