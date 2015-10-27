/*
 * Copyright 2015 Twitter, Inc.
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

public interface ExtendedHeaderListener {

  /**
   * Called by the decoder during header field emission.
   * The name and value byte arrays must not be modified.
   *
   * @param valueAnnotation the previous annotation value stored with the entry.
   * @return the annotation mapping to store with the table entry. If {@code null} is returned
   *         the current mapping is left unchanged.
   */
  public Object addHeader(byte[] name, String nameString, byte[] value, Object valueAnnotation,
                          boolean sensitive);
}