package com.sakshichavan.rtb.bidder;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.net.InetSocketAddress;

/**
 * Netty HTTP server for the bidder.
 * Boss group: 1 thread accepting connections. Worker group: event loop threads (2 per core by default)
 * that handle all reads, writes, and bid evaluation without blocking.
 */
public final class BidderServer implements AutoCloseable {

    private static final int MAX_REQUEST_BYTES = 64 * 1024;

    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final Channel serverChannel;

    public BidderServer(int port, BidEngine engine) throws InterruptedException {
        BidHandler handler = new BidHandler(engine);
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(MAX_REQUEST_BYTES))
                                    .addLast(handler);
                        }
                    });
            this.serverChannel = bootstrap.bind(port).sync().channel();
        } catch (InterruptedException | RuntimeException e) {
            shutdownGroups();
            throw e;
        }
    }

    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @Override
    public void close() {
        serverChannel.close().syncUninterruptibly();
        shutdownGroups();
    }

    private void shutdownGroups() {
        bossGroup.shutdownGracefully().syncUninterruptibly();
        workerGroup.shutdownGracefully().syncUninterruptibly();
    }
}
