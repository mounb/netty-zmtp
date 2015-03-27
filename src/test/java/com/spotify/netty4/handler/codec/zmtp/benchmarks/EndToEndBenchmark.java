/*
 * Copyright (c) 2012-2014 Spotify AB
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

package com.spotify.netty4.handler.codec.zmtp.benchmarks;

import com.google.common.base.Strings;

import com.spotify.netty4.handler.codec.zmtp.ZMTPCodec;
import com.spotify.netty4.handler.codec.zmtp.ZMTPFrame;
import com.spotify.netty4.handler.codec.zmtp.ZMTPIncomingMessage;
import com.spotify.netty4.handler.codec.zmtp.ZMTPMessage;
import com.spotify.netty4.util.BatchFlusher;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import static com.spotify.netty4.handler.codec.zmtp.ZMTPConnectionType.ADDRESSED;
import static com.spotify.netty4.handler.codec.zmtp.ZMTPSocketType.DEALER;
import static com.spotify.netty4.handler.codec.zmtp.ZMTPSocketType.ROUTER;
import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static java.util.Arrays.asList;

public class EndToEndBenchmark {

  private static final InetSocketAddress ANY_PORT = new InetSocketAddress("127.0.0.1", 0);

  public static void main(final String... args) throws InterruptedException {
    final ProgressMeter meter = new ProgressMeter("requests");

    // Codecs
    final ZMTPCodec serverCodec = ZMTPCodec.builder()
        .socketType(ROUTER)
        .connectionType(ADDRESSED)
        .build();

    final ZMTPCodec clientCodec = ZMTPCodec.builder()
        .socketType(DEALER)
        .connectionType(ADDRESSED)
        .build();

    // Server
    final ServerBootstrap serverBootstrap = new ServerBootstrap()
        .group(new NioEventLoopGroup(1), new NioEventLoopGroup())
        .channel(NioServerSocketChannel.class)
        .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
        .childHandler(new ChannelInitializer<NioSocketChannel>() {
          @Override
          protected void initChannel(final NioSocketChannel ch) throws Exception {
            ch.pipeline().addLast(serverCodec);
            ch.pipeline().addLast(new ServerHandler());
          }
        });
    final Channel server = serverBootstrap.bind(ANY_PORT).awaitUninterruptibly().channel();

    // Client
    final SocketAddress address = server.localAddress();
    final Bootstrap clientBootstrap = new Bootstrap()
        .group(new NioEventLoopGroup())
        .channel(NioSocketChannel.class)
        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
        .handler(new ChannelInitializer<NioSocketChannel>() {
          @Override
          protected void initChannel(final NioSocketChannel ch) throws Exception {
            ch.pipeline().addLast(clientCodec);
            ch.pipeline().addLast(new ClientHandler(meter));
          }
        });
    final Channel client = clientBootstrap.connect(address).awaitUninterruptibly().channel();

    // Run until client is closed
    client.closeFuture().await();
  }

  private static class ServerHandler extends ChannelInboundHandlerAdapter {

    private BatchFlusher flusher;

    @Override
    public void channelRegistered(final ChannelHandlerContext ctx) throws Exception {
      super.channelRegistered(ctx);
      this.flusher = new BatchFlusher(ctx.channel());
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
      final ZMTPIncomingMessage message = (ZMTPIncomingMessage) msg;
      ctx.write(message.message());
      flusher.flush();
    }
  }

  private static class ClientHandler extends ChannelInboundHandlerAdapter {

    private static final int CONCURRENCY = 1000;

    private static final ZMTPMessage REQUEST_TEMPLATE = ZMTPMessage.from(true, asList(
        ZMTPFrame.from("envelope1"), ZMTPFrame.from("envelope2"),
        ZMTPFrame.from(""),
        ZMTPFrame.from(EMPTY_BUFFER), // timestamp placeholder
        ZMTPFrame.from(Strings.repeat("d", 20)),
        ZMTPFrame.from(Strings.repeat("d", 40)),
        ZMTPFrame.from(Strings.repeat("d", 100))));

    private final ProgressMeter meter;

    private BatchFlusher flusher;

    public ClientHandler(final ProgressMeter meter) {
      this.meter = meter;
    }

    @Override
    public void channelRegistered(final ChannelHandlerContext ctx) throws Exception {
      super.channelRegistered(ctx);
      this.flusher = new BatchFlusher(ctx.channel());
    }
    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
      super.channelActive(ctx);
      for (int i = 0; i < CONCURRENCY; i++) {
        ctx.write(req());
      }
      flusher.flush();
    }

    private ZMTPMessage req() {
      REQUEST_TEMPLATE.retain();
      final ByteBuf timestamp = PooledByteBufAllocator.DEFAULT.buffer(8);
      timestamp.writeLong(System.nanoTime());
      REQUEST_TEMPLATE.content().set(0, ZMTPFrame.from(timestamp));
      return REQUEST_TEMPLATE;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
      final ZMTPIncomingMessage message = (ZMTPIncomingMessage) msg;
      final long timestamp = message.message().content(0).content().readLong();
      final long latency = System.nanoTime() - timestamp;
      meter.inc(1, latency);
      message.release();
      ctx.write(req());
      flusher.flush();
    }
  }
}
