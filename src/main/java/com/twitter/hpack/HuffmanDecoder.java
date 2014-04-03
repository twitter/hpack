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

import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class HuffmanDecoder {

  private final Node root = new Node();

  /**
   * Creates a new Huffman decoder with the specified Huffman coding.
   * @param codes   the Huffman codes indexed by symbol
   * @param lengths the length of each Huffman code
   */
  HuffmanDecoder(int[] codes, byte[] lengths) {
    buildTree(codes, lengths);
  }

  private void buildTree(int[] codes, byte[] lengths) {
    for (int i = 0; i < lengths.length; i++) {
      addCode(i, codes[i], lengths[i]);
    }
  }

  private void addCode(int sym, int code, byte len) {
    Node terminal = new Node(sym, len);

    Node current = root;
    while (len > 8) {
      len -= 8;
      int i = ((code >>> len) & 0xFF);
      if (current.children == null) {
        throw new IllegalStateException("invalid dictionary: prefix not unique");
      }
      if (current.children[i] == null) {
        current.children[i] = new Node();
      }
      current = current.children[i];
    }

    int shift = 8 - len;
    int start = (code << shift) & 0xFF;
    int end = 1 << shift;
    for (int i = start; i < start + end; i++) {
      current.children[i] = terminal;
    }
  }

  /**
   * Decompresses the given Huffman coded string literal.
   * @param  buf the string literal to be decoded
   * @return the output stream for the compressed data
   * @throws IOException if an I/O error occurs. In particular,
   *         an <code>IOException</code> may be thrown if the
   *         output stream has been closed.
   */
  public byte[] decode(byte[] buf) throws IOException {
    // FIXME
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    Node node = root;
    int current = 0;
    int nbits = 0;
    for (int i = 0; i < buf.length; i++) {
      int b = buf[i] & 0xFF;
      current = (current << 8) | b;
      nbits += 8;
      while (nbits >= 8) {
        int c = (current >>> (nbits - 8)) & 0xFF;
        node = node.children[c];
        if (node.children == null) {
          // terminal node
          baos.write(node.symbol);
          nbits -= node.terminalBits;
          node = root;
        } else {
          // non-terminal node
          nbits -= 8;
        }
      }
    }

    while (nbits > 0) {
      int c = (current << (8 - nbits)) & 0xFF;
      node = node.children[c];
      if (node.children != null || node.terminalBits > nbits) {
        break;
      }
      baos.write(node.symbol);
      nbits -= node.terminalBits;
      node = root;
    }

    return baos.toByteArray();
  }

  private static final class Node {

    // Internal nodes have children
    private final Node[] children;

    // Terminal nodes have a symbol
    private final int symbol;

    // Number of bits represented in the terminal node
    private final int terminalBits;

    /**
     * Construct an internal node
     */
    Node() {
      children = new Node[256];
      symbol = 0;
      terminalBits = 0;
    }

    /**
     * Construct a terminal node
     *
     * @param symbol  symbol the node represents
     * @param bits    length of Huffman code in bits
     */
    Node(int symbol, int bits) {
      this.children = null;
      this.symbol = symbol;
      int b = bits & 0x07;
      this.terminalBits = b == 0 ? 8 : b;
    }
  }
}
