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

/**
 * The result of looking up a header field in a header table or the static table.
 */
final class HeaderIndex {

  static final HeaderIndex NOT_FOUND = new HeaderIndex(-1);

  /**
   * The lowest index value for the header field name in the table.
   * -1 if the header field name is not in the table.
   */
  final int nameIndex;

  /**
   * The lowest index value for the header field in the table.
   * -1 if the header field is not in the table.
   */
  final int fieldIndex;

  HeaderIndex(int nameIndex) {
    this(nameIndex, -1);
  }

  HeaderIndex(int nameIndex, int fieldIndex) {
    this.nameIndex = nameIndex;
    this.fieldIndex = fieldIndex;
  }
}
