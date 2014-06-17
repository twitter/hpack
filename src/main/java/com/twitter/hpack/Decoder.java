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
import java.io.InputStream;

import com.twitter.hpack.HpackUtil.IndexType;

import static com.twitter.hpack.HeaderField.HEADER_ENTRY_OVERHEAD;

public final class Decoder {

  private static final IOException DECOMPRESSION_EXCEPTION = new IOException("decompression failure");
  private static final IOException ILLEGAL_INDEX_VALUE =
      new IOException("illegal index value");
  private static final IOException ILLEGAL_ENCODING_CONTEXT_UPDATE =
      new IOException("illegal encoding context update");
  private static final IOException INVALID_MAX_HEADER_TABLE_SIZE =
      new IOException("invalid max header table size");
  private static final IOException MAX_HEADER_TABLE_SIZE_CHANGE_REQUIRED =
      new IOException("max header table size change required");

  private static final byte[] EMPTY = {};

  private final HeaderTable headerTable;

  private int maxHeaderSize;
  private int maxHeaderTableSize;
  private int encoderMaxHeaderTableSize;
  private boolean maxHeaderTableSizeChangeRequired;

  private long headerSize;
  private State state;
  private IndexType indexType;
  private int index;
  private boolean huffmanEncoded;
  private int skipLength;
  private int nameLength;
  private int valueLength;
  private byte[] name;

  private enum State {
    READ_HEADER_REPRESENTATION,
    READ_MAX_HEADER_TABLE_SIZE,
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

  public Decoder(int maxHeaderSize) {
    this(maxHeaderSize, HpackUtil.DEFAULT_HEADER_TABLE_SIZE);
  }

  public Decoder(int maxHeaderSize, int maxHeaderTableSize) {
    headerTable = new HeaderTable(maxHeaderTableSize);
    this.maxHeaderSize = maxHeaderSize;
    this.maxHeaderTableSize = maxHeaderTableSize;
    encoderMaxHeaderTableSize = maxHeaderTableSize;
    maxHeaderTableSizeChangeRequired = false;
    reset();
  }

  private void reset() {
    headerSize = 0;
    state = State.READ_HEADER_REPRESENTATION;
    indexType = IndexType.NONE;
  }

  /**
   * Decode the header block into header fields.
   */
  public void decode(InputStream in, HeaderListener headerListener) throws IOException {
    while (in.available() > 0) {
      switch(state) {
      case READ_HEADER_REPRESENTATION:
        byte b = (byte) in.read();
        if (maxHeaderTableSizeChangeRequired && (b & 0xF0) != 0x20) {
          // Encoder MUST signal maximum header table size change
          throw MAX_HEADER_TABLE_SIZE_CHANGE_REQUIRED;
        }
        if (b < 0) {
          // Indexed Header Field
          index = b & 0x7F;
          if (index == 0) {
            throw ILLEGAL_INDEX_VALUE;
          } else if (index == 0x7F) {
            state = State.READ_INDEXED_HEADER;
          } else {
            toggleIndex(index, headerListener);
          }
        } else if ((b & 0x40) == 0x40) {
          // Literal Header Field with Incremental Indexing
          indexType = IndexType.INCREMENTAL;
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
        } else if ((b & 0x20) == 0x20) {
          // Encoding Context Update
          index = b & 0x0F;
          if ((b & 0x10) == 0x10) {
            // Reference Set Emptying
            if (index == 0) {
              clearReferenceSet();
              state = State.READ_HEADER_REPRESENTATION;
            } else {
              throw ILLEGAL_ENCODING_CONTEXT_UPDATE;
            }
          } else {
            // Maximum Header Table Size Change
            if (index == 0x0F) {
              state = State.READ_MAX_HEADER_TABLE_SIZE;
            } else {
              setHeaderTableSize(index);
              state = State.READ_HEADER_REPRESENTATION;
            }
          }
        } else {
          // Literal Header Field without Indexing / never Indexed
          indexType = ((b & 0x10) == 0x10) ? IndexType.NEVER : IndexType.NONE;
          index = b & 0x0F;
          if (index == 0) {
            state = State.READ_LITERAL_HEADER_NAME_LENGTH_PREFIX;
          } else if (index == 0x0F) {
            state = State.READ_INDEXED_HEADER_NAME;
          } else {
            // Index was stored as the prefix
            readName(index);
            state = State.READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
          }
        }
        break;

      case READ_MAX_HEADER_TABLE_SIZE:
        int headerTableSize = decodeULE128(in);
        if (headerTableSize == -1) {
          return;
        }

        // Check for numerical overflow
        if (headerTableSize > Integer.MAX_VALUE - index) {
          throw DECOMPRESSION_EXCEPTION;
        }

        setHeaderTableSize(index + headerTableSize);
        state = State.READ_HEADER_REPRESENTATION;
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
              name = EMPTY;
              skipLength = nameLength;
              state = State.SKIP_LITERAL_HEADER_NAME;
              break;
            }

            // Check name length against max header table size
            if (nameLength + HEADER_ENTRY_OVERHEAD > headerTable.capacity()) {
              headerTable.clear();
              name = EMPTY;
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
            name = EMPTY;
            skipLength = nameLength;
            state = State.SKIP_LITERAL_HEADER_NAME;
            break;
          }

          // Check name length against max header table size
          if (nameLength + HEADER_ENTRY_OVERHEAD > headerTable.capacity()) {
            headerTable.clear();
            name = EMPTY;
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

        name = readStringLiteral(in, nameLength);

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
            insertHeader(headerListener, name, EMPTY, indexType);
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

        byte[] value = readStringLiteral(in, valueLength);
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

  /**
   * End the current header block. Returns if the header field has been truncated.
   * This must be called after the header block has been completely decoded.
   */
  public boolean endHeaderBlock(HeaderListener headerListener) {
    for (int index = 1; index <= headerTable.length(); index++) {
      HeaderField headerField = headerTable.getEntry(index);
      if (headerField.inReferenceSet && !headerField.emitted) {
        emitHeader(headerListener, headerField.name, headerField.value, false);
      }
      headerField.emitted = false;
    }
    boolean truncated = headerSize > maxHeaderSize;
    reset();
    return truncated;
  }

  /**
   * Set the maximum header table size.
   * If this is below the maximum size of the header table used by the encoder,
   * the beginning of the next header block MUST signal this change.
   */
  public void setMaxHeaderTableSize(int maxHeaderTableSize) {
    this.maxHeaderTableSize = maxHeaderTableSize;
    if (maxHeaderTableSize < encoderMaxHeaderTableSize) {
      // decoder requires less space than encoder
      // encoder MUST signal this change
      maxHeaderTableSizeChangeRequired = true;
      headerTable.setCapacity(maxHeaderTableSize);
    }
  }

  /**
   * Return the maximum header table size.
   * This is the maximum size allowed by both the encoder and the decoder.
   */
  public int getMaxHeaderTableSize() {
    return headerTable.capacity();
  }

  private void setHeaderTableSize(int headerTableSize) throws IOException {
    if (headerTableSize > maxHeaderTableSize) {
      throw INVALID_MAX_HEADER_TABLE_SIZE;
    }
    encoderMaxHeaderTableSize = headerTableSize;
    maxHeaderTableSizeChangeRequired = false;
    headerTable.setCapacity(headerTableSize);
  }

  private void readName(int index) throws IOException {
    int headerTableLength = headerTable.length();
    if (index <= headerTableLength) {
      HeaderField headerField = headerTable.getEntry(index);
      name = headerField.name;
    } else if (index - headerTableLength <= StaticTable.length) {
      HeaderField headerField = StaticTable.getEntry(index - headerTableLength);
      name = headerField.name;
    } else {
      throw ILLEGAL_INDEX_VALUE;
    }
  }

  private void toggleIndex(int index, HeaderListener headerListener) throws IOException {
    int headerTableLength = headerTable.length();
    if (index <= headerTableLength) {
      HeaderField headerField = headerTable.getEntry(index);
      if (headerField.inReferenceSet) {
        headerField.inReferenceSet = false;
      } else {
        headerField.inReferenceSet = true;
        headerField.emitted = true;
        emitHeader(headerListener, headerField.name, headerField.value, false);
      }
    } else if (index - headerTableLength <= StaticTable.length) {
      HeaderField headerField = StaticTable.getEntry(index - headerTableLength);
      insertHeader(headerListener, headerField.name, headerField.value, IndexType.INCREMENTAL);
    } else {
      throw ILLEGAL_INDEX_VALUE;
    }
  }

  private void insertHeader(HeaderListener headerListener, byte[] name, byte[] value, IndexType indexType) {
    emitHeader(headerListener, name, value, indexType == IndexType.NEVER);

    switch (indexType) {
      case NONE:
      case NEVER:
        break;

      case INCREMENTAL:
        HeaderField headerField = new HeaderField(name, value);
        headerField.emitted = true;
        headerField.inReferenceSet = true;
        headerTable.add(headerField);
        break;

      default:
        throw new IllegalStateException("should not reach here");
    }
  }

  private void emitHeader(HeaderListener headerListener, byte[] name, byte[] value, boolean sensitive) {
    if (name.length == 0) {
      throw new AssertionError("name is empty");
    }
    long newSize = headerSize + name.length + value.length;
    if (newSize <= maxHeaderSize) {
      headerListener.emitHeader(name, value, sensitive);
      headerSize = (int) newSize;
    } else {
      // truncation will be reported during endHeaderBlock
      headerSize = maxHeaderSize + 1;
    }
  }

  private void clearReferenceSet() {
    for (int index = 1; index <= headerTable.length(); index++) {
      HeaderField headerField = headerTable.getEntry(index);
      headerField.inReferenceSet = false;
      headerField.emitted = false;
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

  private byte[] readStringLiteral(InputStream in, int length) throws IOException {
    byte[] buf = new byte[length];
    if (in.read(buf) != length) {
      throw DECOMPRESSION_EXCEPTION;
    }

    if (huffmanEncoded) {
      return Huffman.DECODER.decode(buf);
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
