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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DecompressionTest {

  private static final int MAX_HEADER_SIZE = 8192;

  private Decompressor decompressor;
  private TestHeaders headers;

  private static String hex(String s) {
    return Hex.encodeHexString(s.getBytes());
  }

  private void decode(String encoded) throws IOException {
    byte[] b = Hex.decodeHex(encoded.toCharArray());
    decompressor.decode(new ByteArrayInputStream(b), headers);
  }

  @Before
  public void setUp() {
    decompressor = new Decompressor(true, MAX_HEADER_SIZE);
    headers = new TestHeaders();
  }

  @Test
  public void testEmptyBuffer() throws IOException {
    decode("");
    assertEquals(0, headers.names().size());
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(0, headers.names().size());
  }

  @Test
  public void testIndexedRepresentation() throws IOException {
    // Verify header is emitted when added to reference set
    decode("86");
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":scheme").size());
    assertEquals("http", headers.get(":scheme"));

    // Verify header is not re-emitted during reference set emission
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":scheme").size());

    // Verify header remains in the reference set
    headers.clear();
    assertEquals(0, headers.names().size());
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":scheme").size());
    assertEquals("http", headers.get(":scheme"));

    // Verify header is removed from the reference set
    headers.clear();
    assertEquals(0, headers.names().size());
    decode("81");
    assertEquals(0, headers.names().size());
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(0, headers.names().size());
  }

  @Test
  public void testIndexToggled() throws IOException {
    // Verify header is emitted when added to reference set
    decode("86");
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":scheme").size());
    assertEquals("http", headers.get(":scheme"));

    // Clear headers
    headers.clear();
    assertEquals(0, headers.names().size());

    // Remove header from the reference set
    decode("81");
    assertEquals(0, headers.names().size());
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(0, headers.names().size());

    // Verify header is removed from the reference set
    headers.clear();
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(0, headers.names().size());
  }

  @Test
  public void testIndexEmittedAgain() throws IOException {
    // Verify header is emitted when added to reference set
    decode("86");
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":scheme").size());
    assertEquals("http", headers.get(":scheme"));

    // Remove header from the reference set
    decode("81");
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":scheme").size());

    // Verify header is re-emitted when re-added to reference set
    decode("81");
    assertEquals(1, headers.names().size());
    List<String> values = headers.getAll(":scheme");
    assertEquals(2, values.size());
    for (String value : values) {
      assertEquals("http", value);
    }

    // Verify header is not re-emitted during reference set emission
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(1, headers.names().size());
    assertEquals(2, headers.getAll(":scheme").size());
  }

  @Test
  public void testIncompleteIndex() throws IOException {
    // Verify incomplete indices are unread
    byte[] compressed = Hex.decodeHex("FFF0".toCharArray());
    ByteArrayInputStream in = new ByteArrayInputStream(compressed);
    decompressor.decode(in, headers);
    assertEquals(1, in.available());
    decompressor.decode(in, headers);
    assertEquals(1, in.available());
  }

  @Test(expected = IOException.class)
  public void testIllegalIndex() throws IOException {
    // Index larger than the header table
    decode("FF00");
  }

  @Test(expected = IOException.class)
  public void testInsidiousIndex() throws IOException {
    // Insidious index so the last shift causes sign overflow
    decode("FF8080808008");
  }

  @Test
  public void testLiteralWithoutIndexingWithIndexedName() throws Exception {
    // Verify indexed host header
    decode("410B" + hex("twitter.com"));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":authority").size());
    assertEquals("twitter.com", headers.get(":authority"));

    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":authority").size());
    assertEquals("twitter.com", headers.get(":authority"));

    // Verify header is not added to the reference set
    headers.clear();
    assertEquals(0, headers.names().size());
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(0, headers.names().size());
  }

  @Test
  public void testLiteralWithoutIndexingWithNewName() throws Exception {
    // Verify new header name
    decode("4004" + hex("name") + "05" + hex("value"));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll("name").size());
    assertEquals("value", headers.get("name"));

    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll("name").size());
    assertEquals("value", headers.get("name"));

    // Verify header is not added to the reference set
    headers.clear();
    assertEquals(0, headers.names().size());
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(0, headers.names().size());
  }

  @Test(expected = IOException.class)
  public void testLiteralWithoutIndexingWithEmptyName() throws Exception {
    decode("400005" + hex("value"));
  }

  @Test
  public void testLiteralWithoutIndexingWithEmptyValue() throws Exception {
    // Verify new header name
    decode("4004" + hex("name") + "00");
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll("name").size());
    assertEquals("", headers.get("name"));

    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll("name").size());
    assertEquals("", headers.get("name"));

    // Verify header is not added to the reference set
    headers.clear();
    assertEquals(0, headers.names().size());
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(0, headers.names().size());
  }

  @Test
  public void testLiteralWithoutIndexingWithLargeName() throws Exception {
    // Ignore header name that exceeds max header size
    StringBuilder sb = new StringBuilder();
    sb.append("407F817F");
    for (int i = 0; i < 16384; i++) {
      sb.append("61"); // 'a'
    }
    sb.append("00");
    decode(sb.toString());
    assertEquals(0, headers.names().size());

    // Verify header block is reported as truncated
    assertTrue(decompressor.endHeaderBlock(headers));
    assertEquals(0, headers.names().size());

    // Verify table is unmodified
    decode("86");
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":scheme").size());
    assertEquals("http", headers.get(":scheme"));
  }

  @Test
  public void testLiteralWithoutIndexingWithLargeValue() throws Exception {
    // Ignore header that exceeds max header size
    StringBuilder sb = new StringBuilder();
    sb.append("4004");
    sb.append(hex("name"));
    sb.append("7F813F");
    for (int i = 0; i < 8192; i++) {
      sb.append("61"); // 'a'
    }
    decode(sb.toString());
    assertEquals(0, headers.names().size());

    // Verify header block is reported as truncated
    assertTrue(decompressor.endHeaderBlock(headers));
    assertEquals(0, headers.names().size());

    // Verify table is unmodified
    decode("86");
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":scheme").size());
    assertEquals("http", headers.get(":scheme"));
  }

  @Test
  public void testLiteralWithIncrementalIndexingWithIndexedName() throws Exception {
    // Verify indexed host header
    decode("010B" + hex("twitter.com"));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":authority").size());
    assertEquals("twitter.com", headers.get(":authority"));

    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":authority").size());
    assertEquals("twitter.com", headers.get(":authority"));

    // Verify header is added to the reference set
    headers.clear();
    assertEquals(0, headers.names().size());
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":authority").size());
    assertEquals("twitter.com", headers.get(":authority"));

    // Verify header was insert at new index
    headers.clear();
    assertEquals(0, headers.names().size());
    decode("818181"); // remove, insert and emit, remove
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":authority").size());
    assertEquals("twitter.com", headers.get(":authority"));

    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":authority").size());
    assertEquals("twitter.com", headers.get(":authority"));

    // Verify header is removed from the reference set
    headers.clear();
    assertEquals(0, headers.names().size());
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(0, headers.names().size());
  }

  @Test
  public void testLiteralWithIncrementalIndexingWithNewName() throws Exception {
    // Verify indexed host header
    decode("0004" + hex("name") + "05" + hex("value"));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll("name").size());
    assertEquals("value", headers.get("name"));

    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll("name").size());
    assertEquals("value", headers.get("name"));

    // Verify header is added to the reference set
    headers.clear();
    assertEquals(0, headers.names().size());
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll("name").size());
    assertEquals("value", headers.get("name"));

    // Verify header was insert at new index
    headers.clear();
    assertEquals(0, headers.names().size());
    decode("818181"); // remove, insert and emit, remove
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll("name").size());
    assertEquals("value", headers.get("name"));

    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll("name").size());
    assertEquals("value", headers.get("name"));

    // Verify header is removed from the reference set
    headers.clear();
    assertEquals(0, headers.names().size());
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(0, headers.names().size());
  }

  @Test(expected = IOException.class)
  public void testLiteralWithIncrementalIndexingWithEmptyName() throws Exception {
    decode("000005" + hex("value"));
  }

  @Test
  public void testLiteralWithIncrementalIndexingMultipleEviction() throws Exception {
    // Verify indexed host header
    decode("0005" + hex("name1") + "06" + hex("value1"));
    decode("0005" + hex("name2") + "06" + hex("value2"));
    decode("0005" + hex("name3") + "06" + hex("value3"));
    assertEquals(3, headers.names().size());
    assertEquals(1, headers.getAll("name1").size());
    assertEquals("value1", headers.get("name1"));
    assertEquals(1, headers.getAll("name2").size());
    assertEquals("value2", headers.get("name2"));
    assertEquals(1, headers.getAll("name3").size());
    assertEquals("value3", headers.get("name3"));

    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(3, headers.names().size());
    assertEquals(1, headers.getAll("name1").size());
    assertEquals("value1", headers.get("name1"));
    assertEquals(1, headers.getAll("name2").size());
    assertEquals("value2", headers.get("name2"));
    assertEquals(1, headers.getAll("name3").size());
    assertEquals("value3", headers.get("name3"));

    // Verify headers are added to the reference set
    headers.clear();
    assertEquals(0, headers.names().size());
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(3, headers.names().size());
    assertEquals(1, headers.getAll("name1").size());
    assertEquals("value1", headers.get("name1"));
    assertEquals(1, headers.getAll("name2").size());
    assertEquals("value2", headers.get("name2"));
    assertEquals(1, headers.getAll("name3").size());
    assertEquals("value3", headers.get("name3"));

    // Evicting last 2 elements requires 3979 bytes
    headers.clear();
    StringBuilder sb = new StringBuilder();
    sb.append("0004");
    sb.append(hex("name"));
    sb.append("7F881E");
    for (int i = 0; i < 3975; i++) {
      sb.append("61"); // 'a'
    }
    decode(sb.toString());
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll("name").size());
    assertEquals(3975, headers.get("name").length());

    // Verify 2 elements were evicted from the index
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(2, headers.names().size());
    assertEquals(1, headers.getAll("name").size());
    assertEquals(3975, headers.get("name").length());
    assertEquals(1, headers.getAll("name3").size());
    assertEquals("value3", headers.get("name3"));
  }

  @Test
  public void testLiteralWithIncrementalIndexingCompleteEviction() throws Exception {
    // Verify indexed host header
    decode("0004" + hex("name") + "05" + hex("value"));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll("name").size());
    assertEquals("value", headers.get("name"));

    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll("name").size());
    assertEquals("value", headers.get("name"));

    // Verify header is added to the reference set
    headers.clear();
    assertEquals(0, headers.names().size());
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll("name").size());
    assertEquals("value", headers.get("name"));

    headers.clear();
    StringBuilder sb = new StringBuilder();
    sb.append("027F811F");
    for (int i = 0; i < 4096; i++) {
      sb.append("61"); // 'a'
    }
    decode(sb.toString());
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":authority").size());
    assertEquals(4096, headers.get(":authority").length());

    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(1, headers.names().size());
    assertEquals(1, headers.getAll(":authority").size());
    assertEquals(4096, headers.get(":authority").length());

    // Verify all headers has been evicted from table
    headers.clear();
    assertEquals(0, headers.names().size());
    assertFalse(decompressor.endHeaderBlock(headers));
    assertEquals(0, headers.names().size());

    // Verify next header is inserted at index 0
    headers.clear();
    assertEquals(0, headers.names().size());
    // remove from reference set, insert into reference set and emit
    decode("0004" + hex("name") + "05" + hex("value") + "8181");
    assertEquals(1, headers.names().size());
    List<String> values = headers.getAll("name");
    assertEquals(2, values.size());
    for (String value : values) {
      assertEquals("value", value);
    }
  }

  @Test
  public void testLiteralWithIncrementalIndexingWithLargeName() throws Exception {
    // Ignore header name that exceeds max header size
    StringBuilder sb = new StringBuilder();
    sb.append("007F817F");
    for (int i = 0; i < 16384; i++) {
      sb.append("61"); // 'a'
    }
    sb.append("00");
    decode(sb.toString());
    assertEquals(0, headers.names().size());

    // Verify header block is reported as truncated
    assertTrue(decompressor.endHeaderBlock(headers));
    assertEquals(0, headers.names().size());

    // Verify next header is inserted at index 0
    headers.clear();
    assertEquals(0, headers.names().size());
    // remove from reference set, insert into reference set and emit
    decode("0004" + hex("name") + "05" + hex("value") + "8181");
    assertEquals(1, headers.names().size());
    List<String> values = headers.getAll("name");
    assertEquals(2, values.size());
    for (String value : values) {
      assertEquals("value", value);
    }
  }

  @Test
  public void testLiteralWithIncrementalIndexingWithLargeValue() throws Exception {
    // Ignore header that exceeds max header size
    StringBuilder sb = new StringBuilder();
    sb.append("0004");
    sb.append(hex("name"));
    sb.append("7F813F");
    for (int i = 0; i < 8192; i++) {
      sb.append("61"); // 'a'
    }
    decode(sb.toString());
    assertEquals(0, headers.names().size());

    // Verify header block is reported as truncated
    assertTrue(decompressor.endHeaderBlock(headers));
    assertEquals(0, headers.names().size());

    // Verify next header is inserted at index 0
    headers.clear();
    assertEquals(0, headers.names().size());
    // remove from reference set, insert into reference set and emit
    decode("0004" + hex("name") + "05" + hex("value") + "8181");
    assertEquals(1, headers.names().size());
    List<String> values = headers.getAll("name");
    assertEquals(2, values.size());
    for (String value : values) {
      assertEquals("value", value);
    }
  }
}
