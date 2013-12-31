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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

  private static final String RESPONSE = "response";

  private static final Gson GSON = new GsonBuilder()
    .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
    .registerTypeAdapter(HeaderField.class, new HeaderFieldDeserializer())
    .create();

  int maxHeaderSize;
  String context;
  boolean useIndexing = true;
  boolean forceHuffmanOn;
  boolean forceHuffmanOff;

  List<HeaderBlock> headerBlocks;

  private TestCase() {}

  static TestCase load(InputStream is) throws IOException {
    InputStreamReader r = new InputStreamReader(is);
    TestCase testCase = GSON.fromJson(r, TestCase.class);
    for (HeaderBlock headerBlock : testCase.headerBlocks) {
      headerBlock.encodedBytes = Hex.decodeHex(headerBlock.getEncodedStr().toCharArray());
    }
    return testCase;
  }

  void testCompress() throws Exception {
    Encoder encoder = createEncoder();

    for (int i = 0; i < headerBlocks.size(); i++) {
      HeaderBlock headerBlock = headerBlocks.get(i);

      byte[] actual = encode(encoder, headerBlock.getHeaders(), headerBlock.clearReferenceSet());

      if (!Arrays.equals(actual, headerBlock.encodedBytes)) {
        throw new AssertionError("\nEXPECTED: " + headerBlock.getEncodedStr() + 
            "\nACTUAL  : " + Hex.encodeHexString(actual));
      }
    }
  }

  void testDecompress() throws Exception {
    Decoder decoder = createDecoder();

    for (int i = 0; i < headerBlocks.size(); i++) {
      HeaderBlock headerBlock = headerBlocks.get(i);

      List<HeaderField> actualHeaders = decode(decoder, headerBlock.encodedBytes);

      Collections.sort(actualHeaders);

      List<HeaderField> expectedHeaders = new ArrayList<HeaderField>();
      for (HeaderField h : headerBlock.getHeaders()) {
        expectedHeaders.add(new HeaderField(h.name, h.value));
      }
      Collections.sort(expectedHeaders);

      if (!expectedHeaders.equals(actualHeaders)) {
        throw new AssertionError("\nEXPECTED:\n" + expectedHeaders + "\nACTUAL:\n" + actualHeaders);
      }
    }
  }
  
  void encode(Encoder encoder) throws Exception {
    for (int i = 0; i < headerBlocks.size(); i++) {
      HeaderBlock headerBlock = headerBlocks.get(i);
      encode(encoder, headerBlock.getHeaders(), headerBlock.clearReferenceSet());
    }
  }
  
  void decode(Decoder decoder) throws Exception {
    for (int i = 0; i < headerBlocks.size(); i++) {
      HeaderBlock headerBlock = headerBlocks.get(i);
      decode(decoder, headerBlock.encodedBytes);
    }
  }
  
  private Encoder createEncoder() {
    boolean server = RESPONSE.equalsIgnoreCase(context);

    int maxHeaderSize = this.maxHeaderSize;
    if (maxHeaderSize == 0) {
      maxHeaderSize = HpackUtil.DEFAULT_HEADER_TABLE_SIZE;
    }

    return new Encoder(server, maxHeaderSize, useIndexing, forceHuffmanOn, forceHuffmanOff);
  }

  private Decoder createDecoder() {
    boolean server = !RESPONSE.equalsIgnoreCase(context);

    int maxHeaderSize = this.maxHeaderSize;
    if (maxHeaderSize == 0) {
      maxHeaderSize = HpackUtil.DEFAULT_HEADER_TABLE_SIZE;
    }

    return new Decoder(server, 8192, maxHeaderSize);
  }

  private static byte[] encode(Encoder encoder, List<HeaderField> headers, boolean clearReferenceSet)
      throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    if (clearReferenceSet) {
      encoder.clearReferenceSet(baos);
    }

    for (HeaderField e: headers) {
      encoder.encodeHeader(baos, e.name, e.value);
    }

    encoder.endHeaders(baos);

    return baos.toByteArray();
  }

  private static List<HeaderField> decode(Decoder decoder, byte[] expected) throws IOException {
    List<HeaderField> headers = new ArrayList<HeaderField>();
    TestHeaderListener listener = new TestHeaderListener(headers);
    decoder.decode(new ByteArrayInputStream(expected), listener);
    decoder.endHeaderBlock(listener);
    return headers;
  }

  private static String concat(List<String> l) {
    StringBuilder ret = new StringBuilder();
    for (String s : l) {
      ret.append(s);
    }
    return ret.toString();
  }

  static class HeaderBlock {
    private boolean clearReferenceSet;
    private byte[] encodedBytes;
    private List<String> encoded;
    private List<HeaderField> headers;

    public boolean clearReferenceSet() {
      return clearReferenceSet;
    }

    public String getEncodedStr() {
      return concat(encoded).replaceAll(" ", "");
    }

    public List<HeaderField> getHeaders() {
      return headers;
    }
  }

  static class HeaderFieldDeserializer implements JsonDeserializer<HeaderField> {

    @Override
    public HeaderField deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
        throws JsonParseException {
      JsonObject jsonObject = json.getAsJsonObject();
      Set<Map.Entry<String, JsonElement>> entrySet = jsonObject.entrySet();
      if (entrySet.size() != 1) {
        throw new JsonParseException("JSON Object has multiple entries: " + entrySet);
      }
      Map.Entry<String, JsonElement> entry = entrySet.iterator().next();
      String name = entry.getKey();
      String value = entry.getValue().getAsString();
      return new HeaderField(name, value);
    }
  }
}
