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

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class HpackTest {

  private static final String TEST_DIR = "/hpack/";

  private final String fileName;

  public HpackTest(final String fileName) {
    this.fileName = fileName;
  }

  @Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    final URL url = HpackTest.class.getResource(TEST_DIR);
    final File[] files = new File(url.getFile()).listFiles();
    if (files == null) {
      throw new NullPointerException("files");
    }

    final ArrayList<Object[]> data = new ArrayList<Object[]>();
    for (final File file : files) {
      data.add(new Object[] { file.getName() });
    }
    return data;
  }

  @Test
  public void test() throws Exception {
    final InputStream is = HpackTest.class.getResourceAsStream(TEST_DIR + fileName);
    final TestCase testCase = TestCase.load(is);
    testCase.testCompress();
    testCase.testDecompress();
  }
}
