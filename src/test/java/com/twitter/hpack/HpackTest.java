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
import java.util.List;
import java.util.Map;

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

  private static final String TEST_DIR = "/hpack";

  private static final String RESPONSE = "response";

  private final String fileName;

  public HpackTest(String fileName) {
    this.fileName = fileName;
  }

  @Test
  public void testRoundTrip() throws Exception {
    runTestCase(fileName);
  }

  @Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    ArrayList<Object[]> data = new ArrayList<Object[]>();

    URL url = HpackTest.class.getResource(TEST_DIR);
    File testDir = new File(url.getFile());

    File[] testFiles = testDir.listFiles();
    for (int i = 0; i < testFiles.length; i++) {
      data.add(new Object[] { testFiles[i].getName() });
    }

    return data;
  }

  static void runTestCase(String fileName) throws Exception {
    TestCase testCase = loadTestCase(fileName);
    runTestCase(testCase);
  }

  private static void runTestCase(TestCase testCase) throws Exception {
    Compressor compressor = createCompressor(testCase);
    Decompressor decompressor = createDecompressor(testCase);

    List<HeaderBlock> headerBlocks = testCase.headerBlocks;

    for (int i = 0; i < headerBlocks.size(); i++) {
      HeaderBlock headerBlock = headerBlocks.get(i);
      roundTrip(compressor, decompressor, headerBlock);
    }
  }

  private static void roundTrip(Compressor compressor, Decompressor decompressor,
      HeaderBlock headerBlock) throws Exception {
    byte[] actual = encode(compressor, headerBlock.getHeaders(), headerBlock.clearReferenceSet());
    String expectedHex = headerBlock.getEncoded();
    byte[] expected = Hex.decodeHex(expectedHex.toCharArray());

    if (!Arrays.equals(actual, expected)) {
      throw new AssertionError("\nEXPECTED: " + expectedHex + "\nACTUAL  : " + Hex.encodeHexString(actual));
    }

    TestHeaders actualHeaders = new TestHeaders();
    decompressor.decode(new ByteArrayInputStream(expected), actualHeaders);
    decompressor.endHeaderBlock(actualHeaders);

    TestHeaders expectedHeaders = new TestHeaders();
    for (Header h : headerBlock.getHeaders()) {
      expectedHeaders.add(h.name.toLowerCase(), h.value);
    }

    if (!expectedHeaders.equals(actualHeaders)) {
      throw new AssertionError("\nEXPECTED:\n" + expectedHeaders + "\nACTUAL:\n" + actualHeaders);
    }
  }

  private static TestCase loadTestCase(String fileName) throws IOException {
    InputStream is = HpackTest.class.getResourceAsStream(TEST_DIR + '/' + fileName);
    InputStreamReader r = new InputStreamReader(is);

    GsonBuilder gsonBuilder = new GsonBuilder();
    gsonBuilder.registerTypeAdapter(Header.class, new HeaderDeserializer());
    Gson gson = gsonBuilder.create();

    return gson.fromJson(r, TestCase.class);
  }

  private static Compressor createCompressor(TestCase testCase) {
    boolean server = RESPONSE.equalsIgnoreCase(testCase.context);

    int maxHeaderSize = testCase.maxHeaderSize;
    if (maxHeaderSize == 0) {
      maxHeaderSize = HpackUtil.MAX_HEADER_TABLE_SIZE;
    }

    boolean useIndexing = testCase.useIndexing;
    boolean forceHuffmanOn = testCase.forceHuffmanOn;
    boolean forceHuffmanOff = testCase.forceHuffmanOff;

    return new Compressor(server, maxHeaderSize, useIndexing, forceHuffmanOn, forceHuffmanOff);
  }

  private static Decompressor createDecompressor(TestCase testCase) {
    boolean server = !RESPONSE.equalsIgnoreCase(testCase.context);

    int maxHeaderSize = testCase.maxHeaderSize;
    if (maxHeaderSize == 0) {
      maxHeaderSize = HpackUtil.MAX_HEADER_TABLE_SIZE;
    }

    return new Decompressor(server, 8192, maxHeaderSize);
  }

  private static byte[] encode(Compressor compressor, List<Header> headers, boolean clearReferenceSet)
      throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    if (clearReferenceSet) {
      compressor.clearReferenceSet(baos);
    }

    for (Header e: headers) {
      compressor.encodeHeader(baos, e.getName(), e.getValue());
    }

    compressor.endHeaders(baos);

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
    private List<Header> headers;

    public boolean clearReferenceSet() {
      return clearReferenceSet;
    }

    public String getEncoded() {
      return concat(encoded).replaceAll(" ", "");
    }

    public List<Header> getHeaders() {
      return headers;
    }

  }

  static class Header {
    private final String name;
    private final String value;

    Header(String name, String value) {
      this.name = name;
      this.value = value;
    }

    String getName() {
      return this.name;
    }

    String getValue() {
      return this.value;
    }
  }

  static class HeaderDeserializer implements JsonDeserializer<Header> {

    @Override
    public Header deserialize(JsonElement e, Type t, JsonDeserializationContext ctx) throws JsonParseException {
      JsonObject o = e.getAsJsonObject();

      for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
        String name = entry.getKey();
        String value = entry.getValue().getAsString();
        return new Header(name, value);
      }

      return null;
    }
  }
}
