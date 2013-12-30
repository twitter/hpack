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

import com.twitter.hpack.HpackUtil.ReferenceHeader;

import static com.twitter.hpack.HeaderField.HEADER_ENTRY_OVERHEAD;

public final class Decoder {

  private static final IOException DECOMPRESSION_EXCEPTION = new IOException("decompression failure");

  private final HuffmanDecoder huffmanDecoder;
  private final HeaderTable<ReferenceHeader> headerTable;

  private int maxHeaderSize;
  private long headerSize;

  private State state;
  private IndexType indexType;
  private int index;
  private boolean huffmanEncoded;
  private int skipLength;
  private int nameLength;
  private int valueLength;
  private String name;
  private String value;

  private enum State {
    READ_HEADER_REPRESENTATION,
    READ_INDEXED_HEADER,
    READ_INDEXED_HEADER_NAME,
    READ_LITERAL_HEADER_NAME_LENGTH_PREFIX,
    READ_LITERAL_HEADER_NAME_LENGTH,
    READ_LITERAL_HEADER_NAME,
    SKIP_LITERAL_HEADER_NAME,
    READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX,
    READ_LITERAL_HEADER_VALUE_LENGTH,
    READ_LITERAL_HEADER_VALUE,
    SKIP_LITERAL_HEADER_VALUE
  }

  private enum IndexType {
    NONE,
    INCREMENTAL
  }

  public Decoder(boolean server, int maxHeaderSize) {
    this(server, maxHeaderSize, HpackUtil.DEFAULT_HEADER_TABLE_SIZE);
  }

  public Decoder(boolean server, int maxHeaderSize, int maxHeaderTableSize) {
    this.huffmanDecoder = server ? HpackUtil.REQUEST_DECODER : HpackUtil.RESPONSE_DECODER;
    this.maxHeaderSize = maxHeaderSize;
    headerTable = new HeaderTable<ReferenceHeader>(maxHeaderTableSize);
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
          if (index == 0) {
            clearReferenceSet();
          } else if (index == 0x7F) {
            state = State.READ_INDEXED_HEADER;
          } else {
            toggleIndex(index, headerListener);
          }
        } else {
          // Literal Header Representation
          indexType = ((b & 0x40) == 0x40) ? IndexType.NONE : IndexType.INCREMENTAL;
          index = b & 0x3F;
          if (index == 0) {
            state = State.READ_LITERAL_HEADER_NAME_LENGTH_PREFIX;
          } else if (index == 0x3F) {
            state = State.READ_INDEXED_HEADER_NAME;
          } else {
            // Index was stored as the prefix
            readName(index);
            state = State.READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
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
        state = State.READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
        break;

      case READ_LITERAL_HEADER_NAME_LENGTH_PREFIX:
        b = (byte) in.read();
        huffmanEncoded = (b & 0x80) == 0x80;
        index = b & 0x7F;
        if (index == 0x7f) {
          state = State.READ_LITERAL_HEADER_NAME_LENGTH;
        } else {
          nameLength = index;

          // Disallow empty names -- they cannot be represented in HTTP/1.x
          if (nameLength == 0) {
            throw DECOMPRESSION_EXCEPTION;
          }

          // Check name length against max header size
          if (exceedsMaxHeaderSize(nameLength)) {

            if (indexType == IndexType.NONE) {
              // Name is unused so skip bytes
              name = HpackUtil.EMPTY;
              skipLength = nameLength;
              state = State.SKIP_LITERAL_HEADER_NAME;
              break;
            }

            // Check name length against max header table size
            if (nameLength + HEADER_ENTRY_OVERHEAD > headerTable.capacity()) {
              headerTable.clear();
              name = HpackUtil.EMPTY;
              skipLength = nameLength;
              state = State.SKIP_LITERAL_HEADER_NAME;
              break;
            }
          }
          state = State.READ_LITERAL_HEADER_NAME;
        }
        break;

      case READ_LITERAL_HEADER_NAME_LENGTH:
        // Header Name is a Literal String
        nameLength = decodeULE128(in);
        if (nameLength == -1) {
          return;
        }

        // Check for numerical overflow
        if (nameLength > Integer.MAX_VALUE - index) {
          throw DECOMPRESSION_EXCEPTION;
        }
        nameLength += index;

        // Check name length against max header size
        if (exceedsMaxHeaderSize(nameLength)) {
          if (indexType == IndexType.NONE) {
            // Name is unused so skip bytes
            name = HpackUtil.EMPTY;
            skipLength = nameLength;
            state = State.SKIP_LITERAL_HEADER_NAME;
            break;
          }

          // Check name length against max header table size
          if (nameLength + HEADER_ENTRY_OVERHEAD > headerTable.capacity()) {
            headerTable.clear();
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

        byte[] nameBytes = readString(in, nameLength);
        nameLength = nameBytes.length;
        name = new String(nameBytes, StandardCharsets.UTF_8);

        state = State.READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
        break;

      case SKIP_LITERAL_HEADER_NAME:
        skipLength -= in.skip(skipLength);

        if (skipLength == 0) {
          state = State.READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
        }
        break;

      case READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX:
        b = (byte) in.read();
        huffmanEncoded = (b & 0x80) == 0x80;
        index = b & 0x7F;
        if (index == 0x7f) {
          state = State.READ_LITERAL_HEADER_VALUE_LENGTH;
        } else {
          valueLength = index;

          // Check new header size against max header size
          long newHeaderSize = (long) nameLength + (long) valueLength;
          if (exceedsMaxHeaderSize(newHeaderSize)) {
            // truncation will be reported during endHeaderBlock
            headerSize = maxHeaderSize + 1;

            if (indexType == IndexType.NONE) {
              // Value is unused so skip bytes
              state = State.SKIP_LITERAL_HEADER_VALUE;
              break;
            }

            // Check new header size against max header table size
            if (newHeaderSize + HEADER_ENTRY_OVERHEAD > headerTable.capacity()) {
              headerTable.clear();
              state = State.SKIP_LITERAL_HEADER_VALUE;
              break;
            }
          }

          if (valueLength == 0) {
            value = HpackUtil.EMPTY;
            insertHeader(headerListener, name, value, indexType);
            state = State.READ_HEADER_REPRESENTATION;
          } else {
            state = State.READ_LITERAL_HEADER_VALUE;
          }
        }

        break;

      case READ_LITERAL_HEADER_VALUE_LENGTH:
        // Header Value is a Literal String
        valueLength = decodeULE128(in);
        if (valueLength == -1) {
          return;
        }

        // Check for numerical overflow
        if (valueLength > Integer.MAX_VALUE - index) {
          throw DECOMPRESSION_EXCEPTION;
        }
        valueLength += index;

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
          if (newHeaderSize + HEADER_ENTRY_OVERHEAD > headerTable.capacity()) {
            headerTable.clear();
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

        byte[] valueBytes = readString(in, valueLength);
        valueLength = valueBytes.length;
        value = new String(valueBytes, StandardCharsets.UTF_8);
        insertHeader(headerListener, name, value, indexType);
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
    for (int index = 1; index <= headerTable.length(); index++) {
      ReferenceHeader referenceHeader = headerTable.getEntry(index);
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
    int headerTableLength = headerTable.length();
    if (index <= headerTableLength) {
      ReferenceHeader referenceHeader = headerTable.getEntry(index);
      name = referenceHeader.name;
      nameLength = referenceHeader.nameLength;
    } else if (index - headerTableLength <= StaticTable.length) {
      HeaderField headerField = StaticTable.getEntry(index - headerTableLength);
      name = headerField.name;
      nameLength = headerField.nameLength;
    } else {
      throw DECOMPRESSION_EXCEPTION;
    }
  }

  private void toggleIndex(int index, HeaderListener headerListener) throws IOException {
    int headerTableLength = headerTable.length();
    if (index <= headerTableLength) {
      ReferenceHeader referenceHeader = headerTable.getEntry(index);
      if (referenceHeader.inReferenceSet) {
        referenceHeader.inReferenceSet = false;
      } else {
        referenceHeader.inReferenceSet = true;
        referenceHeader.emitted = true;
        emitHeader(headerListener, referenceHeader.name, referenceHeader.value);
      }
    } else if (index - headerTableLength <= StaticTable.length) {
      HeaderField headerField = StaticTable.getEntry(index - headerTableLength);
      insertHeader(headerListener, headerField.name, headerField.value, IndexType.INCREMENTAL);
    } else {
      throw DECOMPRESSION_EXCEPTION;
    }
  }

  private void insertHeader(HeaderListener headerListener, String name, String value, IndexType indexType) {
    emitHeader(headerListener, name, value);

    switch (indexType) {
      case NONE:
        break;

      case INCREMENTAL:
        ReferenceHeader referenceHeader = new ReferenceHeader(name, value, nameLength, valueLength);
        referenceHeader.emitted = true;
        referenceHeader.inReferenceSet = true;
        headerTable.add(referenceHeader);
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

  private void clearReferenceSet() {
    for (int index = 1; index <= headerTable.length(); index++) {
      ReferenceHeader referenceHeader = headerTable.getEntry(index);
      referenceHeader.inReferenceSet = false;
      referenceHeader.emitted = false;
    }
  }

  private boolean exceedsMaxHeaderSize(long size) {
    // Check new header size against max header size
    if (size + headerSize <= maxHeaderSize) {
      return false;
    }

    // truncation will be reported during endHeaderBlock
    headerSize = maxHeaderSize + 1;
    return true;
  }

  private byte[] readString(InputStream in, int length) throws IOException {
    byte[] buf = new byte[length];
    in.read(buf);

    if (huffmanEncoded) {
      byte[] decoded = huffmanDecoder.decode(buf);
      return decoded;
    } else {
      return buf;
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
