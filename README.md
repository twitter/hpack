HPACK [![Build Status](https://travis-ci.org/twitter/hpack.png?branch=master)](https://travis-ci.org/twitter/hpack) [![Coverage Status](https://coveralls.io/repos/twitter/hpack/badge.png?branch=master)](https://coveralls.io/r/twitter/hpack?branch=master)
=====

[Header Compression for HTTP/2](http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-09)

## Download

HPACK can be downloaded from the Maven central repository. Add the following dependency section to your pom.xml file:

```xml
<dependency>
    <groupId>com.twitter</groupId>
    <artifactId>hpack</artifactId>
    <version>0.9.1</version>
</dependency>
```

## Getting Started

This library provides support for compression of header sets into header blocks. The following code fragment demonstrates the use of Encoder and Decoder:

    try {
      int maxHeaderSize = 4096;
      int maxHeaderTableSize = 4096;
      byte[] name = "name".getBytes();
      byte[] value = "value".getBytes();
      boolean sensitive = false;

      ByteArrayOutputStream out = new ByteArrayOutputStream();

      // encode header set into header block
      Encoder encoder = new Encoder(maxHeaderTableSize);
      encoder.encodeHeader(out, name, value, sensitive);

      ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());

      HeaderListener listener = new HeaderListener() {
        @Override
        public void addHeader(byte[] name, byte[] value, boolean sensitive) {
          // handle header field
        }
      };

      // decode header block into header set
      Decoder decoder = new Decoder(maxHeaderSize, maxHeaderTableSize);
      decoder.decode(in, listener);
      decoder.endHeaderBlock();
    } catch (IOException e) {
      // handle exception
    }

## Problems?
If you find any issues please [report them](https://github.com/twitter/hpack/issues) or better,
send a [pull request](https://github.com/twitter/hpack/pulls).

## Authors
* Jeff Pinner <https://twitter.com/jpinner>
* Bill Gallagher <https://twitter.com/billjgallagher>

## License
Copyright 2013 Twitter, Inc.

Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
