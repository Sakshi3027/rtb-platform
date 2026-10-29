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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Netty HTTP server for the bidder.
 * Boss group: 1 thread accepting connections. Worker group: I/O event loop threads (2 per core by default,
 * or ioThreads if set) that handle reads, writes, and request routing without blocking.
 */
public final class BidderServer implements AutoCloseable {

    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final long SHUTDOWN_QUIET_MILLIS = 100;
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000;

    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup;
    private final Channel serverChannel;

    public BidderServer(int port, BidEngine engine) throws InterruptedException {
        this(port, 0, AsyncBidEngine.of(engine), NoticeSink.NONE, () -> Map.of());
    }

    public BidderServer(int port, BidEngine engine, Supplier<Object> stats) throws InterruptedException {
        this(port, 0, AsyncBidEngine.of(engine), NoticeSink.NONE, stats);
    }

    /** For engines that do I/O, such as BidPipeline. Uses Netty's default I/O thread count. */
    public static BidderServer withAsyncEngine(int port, AsyncBidEngine engine, Supplier<Object> stats)
            throws InterruptedException {
        return new BidderServer(port, 0, engine, NoticeSink.NONE, stats);
    }

    /** @param ioThreads number of I/O event loop threads; 0 means Netty's default (2 per core) */
    public static BidderServer withAsyncEngine(int port, int ioThreads, AsyncBidEngine engine, Supplier<Object> stats)
            throws InterruptedException {
        return new BidderServer(port, ioThreads, engine, NoticeSink.NONE, stats);
    }

    /** Full form: also receives win and loss notices. */
    public static BidderServer withAsyncEngine(int port, int ioThreads, AsyncBidEngine engine, NoticeSink notices,
                                               Supplier<Object> stats) throws InterruptedException {
        return new BidderServer(port, ioThreads, engine, notices, stats);
    }

    private BidderServer(int port, int ioThreads, AsyncBidEngine engine, NoticeSink notices, Supplier<Object> stats)
            throws InterruptedException {
        this.workerGroup = new NioEventLoopGroup(ioThreads);
        BidHandler handler = new BidHandler(engine, notices, stats);
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

    /**
     * Stops accepting work, lets in-flight tasks finish, then stops the threads.
     * Netty's default quiet period is 2s per group, which slows every deploy; 100ms is enough
     * because the server channel is already closed before this runs.
     */
    private void shutdownGroups() {
        bossGroup.shutdownGracefully(SHUTDOWN_QUIET_MILLIS, SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        workerGroup.shutdownGracefully(SHUTDOWN_QUIET_MILLIS, SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        bossGroup.terminationFuture().syncUninterruptibly();
        workerGroup.terminationFuture().syncUninterruptibly();
    }
}
