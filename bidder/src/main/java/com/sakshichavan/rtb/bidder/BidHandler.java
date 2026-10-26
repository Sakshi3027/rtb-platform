package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.BidResponse;
import com.sakshichavan.rtb.common.openrtb.OpenRtbJson;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Routes HTTP requests: POST /bid runs the bid engine, GET /health reports liveness,
 * GET /stats returns operational counters as JSON. Stateless, so one instance is shared (@Sharable).
 */
@ChannelHandler.Sharable
final class BidHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final byte[] HEALTH_BODY = "ok".getBytes(StandardCharsets.UTF_8);

    private final BidEngine engine;
    private final Supplier<Object> stats;

    BidHandler(BidEngine engine, Supplier<Object> stats) {
        this.engine = engine;
        this.stats = stats;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = new QueryStringDecoder(request.uri()).path();
        FullHttpResponse response = switch (path) {
            case "/bid" -> handleBid(request);
            case "/health" -> body(HttpResponseStatus.OK, "text/plain", HEALTH_BODY);
            case "/stats" -> HttpMethod.GET.equals(request.method())
                    ? body(HttpResponseStatus.OK, "application/json", OpenRtbJson.write(stats.get()))
                    : empty(HttpResponseStatus.METHOD_NOT_ALLOWED);
            default -> empty(HttpResponseStatus.NOT_FOUND);
        };
        send(ctx, request, response);
    }

    private FullHttpResponse handleBid(FullHttpRequest request) {
        if (!HttpMethod.POST.equals(request.method())) {
            return empty(HttpResponseStatus.METHOD_NOT_ALLOWED);
        }

        BidRequest bidRequest;
        try {
            bidRequest = OpenRtbJson.readRequest(ByteBufUtil.getBytes(request.content()));
        } catch (UncheckedIOException e) {
            return empty(HttpResponseStatus.BAD_REQUEST);
        }

        Optional<BidResponse> decision;
        try {
            decision = engine.evaluate(bidRequest);
        } catch (RuntimeException e) {
            return empty(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }

        return decision
                .map(bid -> body(HttpResponseStatus.OK, "application/json", OpenRtbJson.write(bid)))
                .orElseGet(() -> empty(HttpResponseStatus.NO_CONTENT));
    }

    private static FullHttpResponse body(HttpResponseStatus status, String contentType, byte[] bytes) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, contentType)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        return response;
    }

    private static FullHttpResponse empty(HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        if (status != HttpResponseStatus.NO_CONTENT) {
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        }
        return response;
    }

    private static void send(ChannelHandlerContext ctx, FullHttpRequest request, FullHttpResponse response) {
        if (HttpUtil.isKeepAlive(request)) {
            HttpUtil.setKeepAlive(response, true);
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
