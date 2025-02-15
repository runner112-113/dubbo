/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.remoting.transport.netty4;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.Codec2;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.exchange.support.MultiMessage;

import java.io.IOException;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * NettyCodecAdapter.
 */
public final class NettyCodecAdapter {

    private final ChannelHandler encoder = new InternalEncoder();

    private final ChannelHandler decoder = new InternalDecoder();

    private final Codec2 codec;

    private final URL url;

    private final org.apache.dubbo.remoting.ChannelHandler handler;

    public NettyCodecAdapter(Codec2 codec, URL url, org.apache.dubbo.remoting.ChannelHandler handler) {
        this.codec = codec;
        this.url = url;
        this.handler = handler;
    }

    public ChannelHandler getEncoder() {
        return encoder;
    }

    public ChannelHandler getDecoder() {
        return decoder;
    }

    private class InternalEncoder extends MessageToByteEncoder {

        @Override
        protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
            boolean encoded = false;

            // 为ByteBuf 则是已经编码过了
            if (msg instanceof ByteBuf) {
                out.writeBytes(((ByteBuf) msg));
                encoded = true;
            } else if (msg instanceof MultiMessage) {
                for (Object singleMessage : ((MultiMessage) msg)) {
                    if (singleMessage instanceof ByteBuf) {
                        ByteBuf buf = (ByteBuf) singleMessage;
                        out.writeBytes(buf);
                        encoded = true;
                        buf.release();
                    }
                }
            }

            // 如果在业务线程没有编码的话 会在此处的I/O线程进行编码
            if (!encoded) {
                ChannelBuffer buffer = new NettyBackedChannelBuffer(out);
                Channel ch = ctx.channel();
                NettyChannel channel = NettyChannel.getOrAddChannel(ch, url, handler);
                codec.encode(channel, buffer, msg);
            }
        }
    }

    private class InternalDecoder extends ByteToMessageDecoder {

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf input, List<Object> out) throws Exception {

            ChannelBuffer message = new NettyBackedChannelBuffer(input);

            NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);

            // decode object.
            // 循环读，可能有多条消息
            do {
                // 先保存读索引
                int saveReaderIndex = message.readerIndex();
                // 此处I/O线程默认不对requestData进行解码
                Object msg = codec.decode(channel, message);
                if (msg == Codec2.DecodeResult.NEED_MORE_INPUT) {
                    // 读到的数据不完整，恢复读索引，等待对端发送更多的数据
                    message.readerIndex(saveReaderIndex);
                    break;
                } else {
                    // is it possible to go here ?
                    if (saveReaderIndex == message.readerIndex()) {
                        throw new IOException("Decode without read data.");
                    }
                    if (msg != null) {
                        out.add(msg);
                    }
                }
            } while (message.readable());
        }
    }
}
