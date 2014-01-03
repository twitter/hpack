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

import java.io.FileInputStream;
import java.io.IOException;

public class PerfTest {

  private static final int TABLE_SIZE = 4096;
  
  private static final int ITERATIONS = 100000;
  
  private static TestCase[] loadTestCases(String[] args) throws IOException {
    TestCase[] testCases = new TestCase[args.length];
    for (int i = 0; i < args.length; i++) {
      FileInputStream fis = new FileInputStream(args[i]);
      testCases[i] = TestCase.load(fis);
      fis.close();
    }
    return testCases;
  }
  
  public static void main(String[] args) throws Exception {
    TestCase[] testCases = loadTestCases(args);

    Encoder encoder = new Encoder(true, TABLE_SIZE, true, false, false);

    long start = System.currentTimeMillis();
    
    for (int i = 0; i < ITERATIONS; i++) {
      for (int j = 0; j < testCases.length; j++) {
        testCases[j].encode(encoder);
      }
    }
    
    long elapsed = System.currentTimeMillis() - start;
    
    System.out.println("ENCODING: " + elapsed);
    
    Thread.sleep(Integer.MAX_VALUE);
  }
}
