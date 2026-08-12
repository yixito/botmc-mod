package dev.botview.remotebot;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpHeaderNames.*;

/**
 * Embedded HTTP + WebSocket server built on Minecraft's bundled Netty.
 * Serves the web UI from classpath /web and exposes /ws for control.
 * Auth: the browser sends the auto-generated token in its first message.
 */
public class RemoteWebServer {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger("remotebot");

    private final Set<Channel> clients = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();
    private EventLoopGroup boss, workers;

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        boss = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup(2);
        try {
            new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(65536));
                            ch.pipeline().addLast(new WebSocketServerCompressionHandler());
                            ch.pipeline().addLast(new WebSocketServerProtocolHandler("/ws", null, true, 262144));
                            ch.pipeline().addLast(new HttpHandler());
                            ch.pipeline().addLast(new WsHandler());
                        }
                    })
                    .bind(RemoteBotMod.PORT).sync();
            LOGGER.info("Web server listening on http://localhost:{}", RemoteBotMod.PORT);
        } catch (Exception e) {
            LOGGER.error("Could not bind web server on port {}: {}", RemoteBotMod.PORT, e.toString());
        }
    }

    public boolean hasClients() {
        return !clients.isEmpty();
    }

    public void broadcast(String json) {
        for (Channel c : clients) {
            if (c.isActive()) {
                c.writeAndFlush(new TextWebSocketFrame(json));
            } else {
                clients.remove(c);
            }
        }
    }

    // ---- HTTP: static files from classpath /web ----

    private static class HttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            String uri = req.uri();
            if (uri.equals("/health")) {
                write(ctx, "application/json", "{\"ok\":true}".getBytes());
                return;
            }
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            if (path.equals("/")) path = "/index.html";
            byte[] bytes = null;
            try (InputStream in = RemoteWebServer.class.getResourceAsStream("/web" + path)) {
                if (in != null) bytes = in.readAllBytes();
            } catch (Exception ignored) {}
            if (bytes == null) {
                write404(ctx, path);
                return;
            }
            write(ctx, contentType(path), bytes);
        }

        private void write404(ChannelHandlerContext ctx, String path) {
            byte[] b = ("not found: " + path).getBytes();
            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, NOT_FOUND, Unpooled.wrappedBuffer(b));
            resp.headers().set(CONTENT_TYPE, "text/plain").setInt(CONTENT_LENGTH, b.length);
            ctx.writeAndFlush(resp);
        }

        private void write(ChannelHandlerContext ctx, String type, byte[] bytes) {
            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, OK, Unpooled.wrappedBuffer(bytes));
            resp.headers().set(CONTENT_TYPE, type).setInt(CONTENT_LENGTH, bytes.length).set(CACHE_CONTROL, "no-cache");
            ctx.writeAndFlush(resp);
        }

        private static String contentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=utf-8";
            if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (path.endsWith(".css")) return "text/css; charset=utf-8";
            if (path.endsWith(".svg")) return "image/svg+xml";
            return "application/octet-stream";
        }
    }

    // ---- WebSocket: authenticated control ----

    private static class WsHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private boolean authed;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (!(frame instanceof TextWebSocketFrame text)) {
                ctx.close();
                return;
            }
            Map<String, Object> m;
            try {
                m = RemoteBotMod.GSON.fromJson(text.text(), Map.class);
            } catch (Exception e) {
                ctx.close();
                return;
            }
            String type = (String) m.get("type");
            if (!authed) {
                if ("hello".equals(type) && RemoteBotMod.token.equals(m.get("t"))) {
                    authed = true;
                    RemoteBotMod.web.clients.add(ctx.channel());
                    ctx.writeAndFlush(new TextWebSocketFrame("{\"type\":\"hello\",\"ok\":true}"));
                } else {
                    ctx.writeAndFlush(new TextWebSocketFrame("{\"type\":\"error\",\"message\":\"unauthorized\"}"));
                    ctx.close();
                }
                return;
            }
            if (type == null) return;
            BotManager bots = RemoteBotMod.bots;
            if (bots == null) return;
            switch (type) {
                case "input" -> bots.queueAction(() -> bots.setRemoteKeys(
                        b(m.get("f")), b(m.get("b")), b(m.get("l")), b(m.get("r")), b(m.get("j"))));
                case "look" -> bots.queueAction(() -> bots.setLook(
                        num(m.get("yaw")), num(m.get("pitch"))));
                case "goto" -> bots.queueAction(() -> bots.goTo(
                        (int) Math.floor(num(m.get("x"))), (int) Math.floor(num(m.get("y"))), (int) Math.floor(num(m.get("z")))));
                case "mode" -> bots.queueAction(() -> bots.setMode(str(m.get("mode"))));
                case "attack" -> bots.queueAction(bots::attack);
                case "stop" -> bots.queueAction(bots::stopBot);
                case "resume" -> bots.queueAction(bots::resume);
                case "ping" -> ctx.writeAndFlush(new TextWebSocketFrame("{\"type\":\"pong\"}"));
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            RemoteBotMod.web.clients.remove(ctx.channel());
            BotManager bots = RemoteBotMod.bots;
            if (bots != null) bots.queueAction(() -> bots.setRemoteKeys(false, false, false, false, false));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }

        private static boolean b(Object o) { return Boolean.TRUE.equals(o); }
        private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
        private static String str(Object o) { return o instanceof String s ? s : ""; }
    }
}
