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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Routes HTTP requests: POST /bid runs the async bid engine, GET /win and GET /loss receive auction outcome
 * notices, GET /health reports liveness, GET /stats returns counters as JSON.
 * Stateless, so one instance is shared (@Sharable). Bid responses are written when the decision completes,
 * which may be on another thread; Netty hands the write back to this channel's event loop safely.
 */
@ChannelHandler.Sharable
final class BidHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final byte[] HEALTH_BODY = "ok".getBytes(StandardCharsets.UTF_8);

    private final AsyncBidEngine engine;
    private final NoticeSink notices;
    private final Supplier<Object> stats;

    BidHandler(AsyncBidEngine engine, NoticeSink notices, Supplier<Object> stats) {
        this.engine = engine;
        this.notices = notices;
        this.stats = stats;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        QueryStringDecoder query = new QueryStringDecoder(request.uri());
        String path = query.path();
        if (path.equals("/bid")) {
            handleBid(ctx, request, keepAlive);
            return;
        }
        FullHttpResponse response = switch (path) {
            case "/win" -> handleWin(query);
            case "/loss" -> handleLoss(query);
            case "/health" -> body(HttpResponseStatus.OK, "text/plain", HEALTH_BODY);
            case "/stats" -> HttpMethod.GET.equals(request.method())
                    ? body(HttpResponseStatus.OK, "application/json", OpenRtbJson.write(stats.get()))
                    : empty(HttpResponseStatus.METHOD_NOT_ALLOWED);
            default -> empty(HttpResponseStatus.NOT_FOUND);
        };
        send(ctx, keepAlive, response);
    }

    /** Win notice: /win?bid=ID&price=CPM. Repeats are safe; the sink settles each bid at most once. */
    private FullHttpResponse handleWin(QueryStringDecoder query) {
        String bidId = firstParameter(query, "bid");
        String price = firstParameter(query, "price");
        if (bidId == null || price == null) {
            return empty(HttpResponseStatus.BAD_REQUEST);
        }
        double clearingPriceCpm;
        try {
            clearingPriceCpm = Double.parseDouble(price);
        } catch (NumberFormatException e) {
            return empty(HttpResponseStatus.BAD_REQUEST);
        }
        if (!(clearingPriceCpm >= 0)) {
            return empty(HttpResponseStatus.BAD_REQUEST);
        }
        try {
            notices.win(bidId, clearingPriceCpm);
        } catch (RuntimeException e) {
            return empty(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
        return empty(HttpResponseStatus.NO_CONTENT);
    }

    /** Loss notice: /loss?bid=ID. */
    private FullHttpResponse handleLoss(QueryStringDecoder query) {
        String bidId = firstParameter(query, "bid");
        if (bidId == null) {
            return empty(HttpResponseStatus.BAD_REQUEST);
        }
        try {
            notices.loss(bidId);
        } catch (RuntimeException e) {
            return empty(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
        return empty(HttpResponseStatus.NO_CONTENT);
    }

    private static String firstParameter(QueryStringDecoder query, String name) {
        List<String> values = query.parameters().get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private void handleBid(ChannelHandlerContext ctx, FullHttpRequest request, boolean keepAlive) {
        if (!HttpMethod.POST.equals(request.method())) {
            send(ctx, keepAlive, empty(HttpResponseStatus.METHOD_NOT_ALLOWED));
            return;
        }

        BidRequest bidRequest;
        try {
            bidRequest = OpenRtbJson.readRequest(ByteBufUtil.getBytes(request.content()));
        } catch (UncheckedIOException e) {
            send(ctx, keepAlive, empty(HttpResponseStatus.BAD_REQUEST));
            return;
        }

        CompletionStage<Optional<BidResponse>> decision;
        try {
            decision = engine.evaluate(bidRequest);
        } catch (RuntimeException e) {
            send(ctx, keepAlive, empty(HttpResponseStatus.INTERNAL_SERVER_ERROR));
            return;
        }

        decision.whenComplete((bid, error) -> send(ctx, keepAlive, toResponse(bid, error)));
    }

    private static FullHttpResponse toResponse(Optional<BidResponse> bid, Throwable error) {
        if (error != null) {
            return empty(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
        try {
            return bid.map(b -> body(HttpResponseStatus.OK, "application/json", OpenRtbJson.write(b)))
                    .orElseGet(() -> empty(HttpResponseStatus.NO_CONTENT));
        } catch (RuntimeException e) {
            return empty(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
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

    private static void send(ChannelHandlerContext ctx, boolean keepAlive, FullHttpResponse response) {
        if (keepAlive) {
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
