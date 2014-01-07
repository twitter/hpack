HPACK [![Build Status](https://travis-ci.org/twitter/hpack.png?branch=master)](https://travis-ci.org/twitter/hpack)
=====

[Header Compression for HTTP/2.0](http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05)

## Getting Started

This library provides support for compression of header sets into header blocks. The following code fragment demonstrates the use of Encoder and Decoder:

    try {
      int maxHeaderSize = 4096;
      int maxHeaderTableSize = 4096;
      byte[] name = "name".getBytes();
      byte[] value = "value".getBytes();

      ByteArrayOutputStream out = new ByteArrayOutputStream();

      // encode header set into header block
      Encoder encoder = new Encoder(false, maxHeaderTableSize);
      encoder.encodeHeader(out, name, value);
      encoder.endHeaders(out);

      ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());

      HeaderListener listener = new HeaderListener() {
        @Override
        public void emitHeader(byte[] name, byte[] value) {
          // handle header field
        }
      };

      // decode header block into header set
      Decoder decoder = new Decoder(true, maxHeaderSize, maxHeaderTableSize);
      decoder.decode(in, listener);
      decoder.endHeaderBlock(listener);
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
