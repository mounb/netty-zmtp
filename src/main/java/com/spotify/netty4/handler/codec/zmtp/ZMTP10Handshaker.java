/*
 * Copyright (c) 2012-2015 Spotify AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.spotify.netty4.handler.codec.zmtp;


import java.nio.ByteBuffer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

import static com.spotify.netty4.handler.codec.zmtp.ZMTPUtils.encodeZMTP1Length;

public class ZMTP10Handshaker implements ZMTPHandshaker {

  private final ByteBuffer localIdentity;

  public ZMTP10Handshaker(final ByteBuffer localIdentity) {
    this.localIdentity = localIdentity;
  }

  @Override
  public ByteBuf onConnect() {
    return makeZMTP1Greeting();
  }

  @Override
  public ZMTPHandshake inputOutput(final ByteBuf buffer, final ChannelHandlerContext channel) throws ZMTPException {
    final byte[] remoteIdentity = ZMTPUtils.readZMTP1RemoteIdentity(buffer);
    return new ZMTPHandshake(1, ByteBuffer.wrap(remoteIdentity));
  }

  /**
   * Create and return a ByteBuf containing an ZMTP/1.0 greeting based on on the constructor
   * provided session.
   *
   * @return a ByteBuf with a greeting
   */
  private ByteBuf makeZMTP1Greeting() {
    ByteBuf out = Unpooled.buffer();
    encodeZMTP1Length(localIdentity.remaining() + 1, out);
    out.writeByte(0x00);
    out.writeBytes(localIdentity.duplicate());
    return out;
  }

}
