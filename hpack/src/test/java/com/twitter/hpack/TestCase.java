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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

final class TestCase {

  private static final Gson GSON = new GsonBuilder()
      .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
      .registerTypeAdapter(HeaderField.class, new HeaderFieldDeserializer())
      .create();

  final int maxHeaderTableSize = -1;
  final boolean useIndexing = true;
  boolean sensitiveHeaders;
  boolean forceHuffmanOn;
  boolean forceHuffmanOff;

  List<HeaderBlock> headerBlocks;

  private TestCase() {}

  static TestCase load(final InputStream is) throws IOException {
    final InputStreamReader r = new InputStreamReader(is);
    final TestCase testCase = GSON.fromJson(r, TestCase.class);
    for (final HeaderBlock headerBlock : testCase.headerBlocks) {
      headerBlock.encodedBytes = Hex.decodeHex(headerBlock.getEncodedStr().toCharArray());
    }
    return testCase;
  }

  void testCompress() throws Exception {
    final Encoder encoder = createEncoder();

    for (final HeaderBlock headerBlock : headerBlocks) {

      final byte[] actual = encode(encoder, headerBlock.getHeaders(), headerBlock.getMaxHeaderTableSize(), sensitiveHeaders);

      if (!Arrays.equals(actual, headerBlock.encodedBytes)) {
        throw new AssertionError(
            "\nEXPECTED:\n" + headerBlock.getEncodedStr() +
            "\nACTUAL:\n" + Hex.encodeHexString(actual));
      }

      final List<HeaderField> actualDynamicTable = new ArrayList<HeaderField>();
      for (int index = 0; index < encoder.length(); index++) {
        actualDynamicTable.add(encoder.getHeaderField(index));
      }

      final List<HeaderField> expectedDynamicTable = headerBlock.getDynamicTable();

      if (!expectedDynamicTable.equals(actualDynamicTable)) {
        throw new AssertionError(
            "\nEXPECTED DYNAMIC TABLE:\n" + expectedDynamicTable +
            "\nACTUAL DYNAMIC TABLE:\n" + actualDynamicTable);
      }

      if (headerBlock.getTableSize() != encoder.size()) {
        throw new AssertionError(
            "\nEXPECTED TABLE SIZE: " + headerBlock.getTableSize() +
            "\n ACTUAL TABLE SIZE : " + encoder.size());
      }
    }
  }

  void testDecompress() throws Exception {
    final Decoder decoder = createDecoder();

    for (final HeaderBlock headerBlock : headerBlocks) {

      final List<HeaderField> actualHeaders = decode(decoder, headerBlock.encodedBytes);

      final List<HeaderField> expectedHeaders = new ArrayList<HeaderField>();
      for (final HeaderField h : headerBlock.getHeaders()) {
        expectedHeaders.add(new HeaderField(h.name, h.value));
      }

      if (!expectedHeaders.equals(actualHeaders)) {
        throw new AssertionError(
            "\nEXPECTED:\n" + expectedHeaders +
            "\nACTUAL:\n" + actualHeaders);
      }

      final List<HeaderField> actualDynamicTable = new ArrayList<HeaderField>();
      for (int index = 0; index < decoder.length(); index++) {
        actualDynamicTable.add(decoder.getHeaderField(index));
      }

      final List<HeaderField> expectedDynamicTable = headerBlock.getDynamicTable();

      if (!expectedDynamicTable.equals(actualDynamicTable)) {
        throw new AssertionError(
            "\nEXPECTED DYNAMIC TABLE:\n" + expectedDynamicTable +
            "\nACTUAL DYNAMIC TABLE:\n" + actualDynamicTable);
      }

      if (headerBlock.getTableSize() != decoder.size()) {
        throw new AssertionError(
            "\nEXPECTED TABLE SIZE: " + headerBlock.getTableSize() +
            "\n ACTUAL TABLE SIZE : " + decoder.size());
      }
    }
  }

  private Encoder createEncoder() {
    int maxHeaderTableSize = this.maxHeaderTableSize;
    if (maxHeaderTableSize == -1) {
      maxHeaderTableSize = Integer.MAX_VALUE;
    }

    return new Encoder(maxHeaderTableSize, useIndexing, forceHuffmanOn, forceHuffmanOff);
  }

  private Decoder createDecoder() {
    int maxHeaderTableSize = this.maxHeaderTableSize;
    if (maxHeaderTableSize == -1) {
      maxHeaderTableSize = Integer.MAX_VALUE;
    }

    return new Decoder(8192, maxHeaderTableSize);
  }

  private static byte[] encode(final Encoder encoder, final List<HeaderField> headers, final int maxHeaderTableSize, final boolean sensitive)
      throws IOException {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();

    if (maxHeaderTableSize != -1) {
      encoder.setMaxHeaderTableSize(baos, maxHeaderTableSize);
    }

    for (final HeaderField e: headers) {
      encoder.encodeHeader(baos, e.name, e.value, sensitive);
    }

    return baos.toByteArray();
  }

  private static List<HeaderField> decode(final Decoder decoder, final byte[] expected) throws IOException {
    final List<HeaderField> headers = new ArrayList<HeaderField>();
    final TestHeaderListener listener = new TestHeaderListener(headers);
    decoder.decode(new ByteArrayInputStream(expected), listener);
    decoder.endHeaderBlock();
    return headers;
  }

  private static String concat(final List<String> l) {
    final StringBuilder ret = new StringBuilder();
    for (final String s : l) {
      ret.append(s);
    }
    return ret.toString();
  }

  static class HeaderBlock {
    private final int maxHeaderTableSize = -1;
    private byte[] encodedBytes;
    private List<String> encoded;
    private List<HeaderField> headers;
    private List<HeaderField> dynamicTable;
    private int tableSize;

    private int getMaxHeaderTableSize() {
      return maxHeaderTableSize;
    }

    public String getEncodedStr() {
      return concat(encoded).replaceAll(" ", "");
    }

    public List<HeaderField> getHeaders() {
      return headers;
    }

    public List<HeaderField> getDynamicTable() {
      return dynamicTable;
    }

    public int getTableSize() {
      return tableSize;
    }
  }

  static class HeaderFieldDeserializer implements JsonDeserializer<HeaderField> {

    @Override
    public HeaderField deserialize(final JsonElement json, final Type typeOfT, final JsonDeserializationContext context)
        throws JsonParseException {
      final JsonObject jsonObject = json.getAsJsonObject();
      final Set<Map.Entry<String, JsonElement>> entrySet = jsonObject.entrySet();
      if (entrySet.size() != 1) {
        throw new JsonParseException("JSON Object has multiple entries: " + entrySet);
      }
      final Map.Entry<String, JsonElement> entry = entrySet.iterator().next();
      final String name = entry.getKey();
      final String value = entry.getValue().getAsString();
      return new HeaderField(name, value);
    }
  }
}
