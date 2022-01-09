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
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

public class HuffmanTest {

  @Test
  public void testHuffman() throws IOException {

    final String s = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    for (int i = 0; i < s.length(); i++) {
      roundTrip(s.substring(0, i));
    }

    final Random random = new Random(123456789L);
    final byte[] buf = new byte[4096];
    random.nextBytes(buf);
    roundTrip(buf);
  }

  @Test(expected = IOException.class)
  public void testDecodeEOS() throws IOException {
    final byte[] buf = new byte[4];
    for (int i = 0; i < 4; i++) {
      buf[i] = (byte) 0xFF;
    }
    Huffman.DECODER.decode(buf);
  }

  @Test(expected = IOException.class)
  public void testDecodeIllegalPadding() throws IOException {
    final byte[] buf = new byte[1];
    buf[0] = 0x00; // '0', invalid padding
    Huffman.DECODER.decode(buf);
  }

  @Test//(expected = IOException.class) TODO(jpinner) fix me
  public void testDecodeExtraPadding() throws IOException {
    final byte[] buf = new byte[2];
    buf[0] = 0x0F; // '1', 'EOS'
    buf[1] = (byte) 0xFF; // 'EOS'
    Huffman.DECODER.decode(buf);
  }

  private void roundTrip(final String s) throws IOException {
    roundTrip(Huffman.ENCODER, Huffman.DECODER, s);
  }

  private static void roundTrip(final HuffmanEncoder encoder, final HuffmanDecoder decoder, final String s) throws IOException {
    roundTrip(encoder, decoder, s.getBytes());
  }

  private void roundTrip(final byte[] buf) throws IOException {
    roundTrip(Huffman.ENCODER, Huffman.DECODER, buf);
  }

  private static void roundTrip(final HuffmanEncoder encoder, final HuffmanDecoder decoder, final byte[] buf) throws IOException {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    final DataOutputStream dos = new DataOutputStream(baos);

    encoder.encode(dos, buf);

    final byte[] actualBytes = decoder.decode(baos.toByteArray());

    Assert.assertArrayEquals(buf, actualBytes);
  }
}
