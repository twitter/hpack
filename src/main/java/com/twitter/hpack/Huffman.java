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

import static com.twitter.hpack.HpackUtil.REQUEST_CODE_LENGTHS;
import static com.twitter.hpack.HpackUtil.REQUEST_CODES;
import static com.twitter.hpack.HpackUtil.RESPONSE_CODE_LENGTHS;
import static com.twitter.hpack.HpackUtil.RESPONSE_CODES;

public final class Huffman {

  /**
   * Huffman Decoder used in the client to server direction.
   */
  public static final HuffmanDecoder REQUEST_DECODER = new HuffmanDecoder(REQUEST_CODES, REQUEST_CODE_LENGTHS);

  /**
   * Huffman Encoder used in the client to server direction.
   */
  public static final HuffmanEncoder REQUEST_ENCODER = new HuffmanEncoder(REQUEST_CODES, REQUEST_CODE_LENGTHS);

  /**
   * Huffman Decoder used in the server to client direction.
   */
  public static final HuffmanDecoder RESPONSE_DECODER = new HuffmanDecoder(RESPONSE_CODES, RESPONSE_CODE_LENGTHS);

  /**
   * Huffman Encoder used in the server to client direction.
   */
  public static final HuffmanEncoder RESPONSE_ENCODER = new HuffmanEncoder(RESPONSE_CODES, RESPONSE_CODE_LENGTHS);

  private Huffman() {
    // utility class
  }
}
