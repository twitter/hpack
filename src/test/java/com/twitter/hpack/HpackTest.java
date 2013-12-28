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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class HpackTest {

  private static final String TEST_DIR = "/hpack/";

  private static final String RESPONSE = "response";

  private static final Gson GSON = new GsonBuilder()
      .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
      .registerTypeAdapter(HeaderField.class, new HeaderFieldDeserializer())
      .create();

  private final String fileName;

  public HpackTest(String fileName) {
    this.fileName = fileName;
  }

  @Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    URL url = HpackTest.class.getResource(TEST_DIR);
    File[] files = new File(url.getFile()).listFiles();
    if (files == null) {
      throw new NullPointerException("files");
    }

    ArrayList<Object[]> data = new ArrayList<Object[]>();
    for (File file : files) {
      data.add(new Object[] { file.getName() });
    }
    return data;
  }

  @Test
  public void test() throws Exception {
    TestCase testCase = loadTestCase(fileName);
    runTestCase(testCase);
  }

  private static void runTestCase(TestCase testCase) throws Exception {
    Encoder encoder = createEncoder(testCase);
    Decoder decoder = createDecoder(testCase);

    List<HeaderBlock> headerBlocks = testCase.headerBlocks;

    for (int i = 0; i < headerBlocks.size(); i++) {
      HeaderBlock headerBlock = headerBlocks.get(i);
      roundTrip(encoder, decoder, headerBlock);
    }
  }

  private static void roundTrip(Encoder encoder, Decoder decoder,
      HeaderBlock headerBlock) throws Exception {
    byte[] actual = encode(encoder, headerBlock.getHeaders(), headerBlock.clearReferenceSet());
    String expectedHex = headerBlock.getEncoded();
    byte[] expected = Hex.decodeHex(expectedHex.toCharArray());

    if (!Arrays.equals(actual, expected)) {
      throw new AssertionError("\nEXPECTED: " + expectedHex + "\nACTUAL  : " + Hex.encodeHexString(actual));
    }

    List<HeaderField> actualHeaders = new ArrayList<HeaderField>();
    TestHeaderListener listener = new TestHeaderListener(actualHeaders);
    decoder.decode(new ByteArrayInputStream(expected), listener);
    decoder.endHeaderBlock(listener);
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

  private static TestCase loadTestCase(String fileName) throws IOException {
    InputStream is = HpackTest.class.getResourceAsStream(TEST_DIR + fileName);
    InputStreamReader r = new InputStreamReader(is);
    return GSON.fromJson(r, TestCase.class);
  }

  private static Encoder createEncoder(TestCase testCase) {
    boolean server = RESPONSE.equalsIgnoreCase(testCase.context);

    int maxHeaderSize = testCase.maxHeaderSize;
    if (maxHeaderSize == 0) {
      maxHeaderSize = HpackUtil.DEFAULT_HEADER_TABLE_SIZE;
    }

    boolean useIndexing = testCase.useIndexing;
    boolean forceHuffmanOn = testCase.forceHuffmanOn;
    boolean forceHuffmanOff = testCase.forceHuffmanOff;

    return new Encoder(server, maxHeaderSize, useIndexing, forceHuffmanOn, forceHuffmanOff);
  }

  private static Decoder createDecoder(TestCase testCase) {
    boolean server = !RESPONSE.equalsIgnoreCase(testCase.context);

    int maxHeaderSize = testCase.maxHeaderSize;
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

  private static String concat(List<String> l) {
    StringBuilder ret = new StringBuilder();
    for (String s : l) {
      ret.append(s);
    }
    return ret.toString();
  }

  static class TestCase {
    int maxHeaderSize;
    String context;
    boolean useIndexing = true;
    boolean forceHuffmanOn;
    boolean forceHuffmanOff;

    List<HeaderBlock> headerBlocks;
  }

  static class HeaderBlock {
    private boolean clearReferenceSet;
    private List<String> encoded;
    private List<HeaderField> headers;

    public boolean clearReferenceSet() {
      return clearReferenceSet;
    }

    public String getEncoded() {
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
