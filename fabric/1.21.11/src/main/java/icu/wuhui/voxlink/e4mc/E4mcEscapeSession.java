package icu.wuhui.voxlink.e4mc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * e4mc 公共中继逃生通道会话。
 * 协议逐语句对齐 e4mc (MIT, vgskye) QuiclimeSession 6.x。
 * MC childHandler 由 mixin 捕获, QUIC 流直接承载 MC 连接。
 */
public class E4mcEscapeSession {
   private static final Gson GSON = new Gson();
   private static final String BROKER_URL = "https://broker.e4mc.link/getBestRelay";

   public enum State {
      STARTING,
      STARTED,
      UNHEALTHY,
      STOPPING,
      STOPPED
   }

   private static class BrokerResponse {
      String id;
      String host;
      int port;
   }

   private static class ControlMessageCodec extends ByteToMessageCodec<ControlMessageCodec.ControlMessage> {
      public interface ControlMessage {
      }

      public static class RequestDomainAssignment implements ControlMessage {
         String kind = "request_domain_assignment";
      }

      public static class DomainAssignmentComplete implements ControlMessage {
         String kind = "domain_assignment_complete";
         String domain;
      }

      public static class MessageBroadcast implements ControlMessage {
         String kind = "request_message_broadcast";
         String message;
      }

      @Override
      protected void encode(ChannelHandlerContext ctx, ControlMessage msg, ByteBuf out) {
         byte[] json = GSON.toJson(msg).getBytes(StandardCharsets.UTF_8);
         out.writeByte(json.length);
         out.writeBytes(json);
      }

      @Override
      protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
         int size = in.getByte(in.readerIndex());
         if (in.readableBytes() >= size + 1) {
            in.skipBytes(1);
            byte[] buf = new byte[size];
            in.readBytes(buf);
            JsonObject json = GSON.fromJson(new String(buf, StandardCharsets.UTF_8), JsonObject.class);
            String kind = json.get("kind").getAsString();
            if ("domain_assignment_complete".equals(kind)) {
               out.add(GSON.fromJson(json, DomainAssignmentComplete.class));
            } else if ("request_message_broadcast".equals(kind)) {
               out.add(GSON.fromJson(json, MessageBroadcast.class));
            } else {
               throw new RuntimeException("Invalid control message kind: " + kind);
            }
         }
      }
   }

   public volatile State state = State.STARTING;
   private final ChannelHandler childHandler;
   private final EventLoopGroup group;
   private final Consumer<String> onDomainAssigned;
   private final Consumer<String> onBroadcast;
   private DatagramChannel datagramChannel;
   private volatile QuicChannel quicChannel;

   public E4mcEscapeSession(
      ChannelHandler childHandler,
      EventLoopGroup group,
      Consumer<String> onDomainAssigned,
      Consumer<String> onBroadcast
   ) {
      this.childHandler = childHandler;
      this.group = group;
      this.onDomainAssigned = onDomainAssigned;
      this.onBroadcast = onBroadcast;
   }

   public boolean isRunning() {
      State s = this.state;
      return s == State.STARTING || s == State.STARTED;
   }

   public void start() {
      Thread t = new Thread(this::start0, "VoxLink-e4mc-init");
      t.setDaemon(true);
      t.start();
   }

   private static BrokerResponse getRelay() throws Exception {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request = HttpRequest.newBuilder(new URI(BROKER_URL))
         .header("Accept", "application/json")
         .build();
      HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
         throw new RuntimeException("broker http " + resp.statusCode());
      }

      return GSON.fromJson(resp.body(), BrokerResponse.class);
   }

   private static Class<? extends DatagramChannel> datagramChannelClass(EventLoopGroup group) {
      if (group instanceof EpollEventLoopGroup) {
         return EpollDatagramChannel.class;
      } else {
         return NioDatagramChannel.class;
      }
   }

   private void start0() {
      try {
         BrokerResponse relay = getRelay();
         QuicSslContext context = QuicSslContextBuilder
            .forClient()
            .applicationProtocols("quiclime")
            .build();
         ChannelHandler codec = new QuicClientCodecBuilder()
            .sslContext(context)
            .sslEngineProvider(it -> context.newEngine(it.alloc(), relay.host, relay.port))
            .initialMaxStreamsBidirectional(512)
            .maxIdleTimeout(10L, TimeUnit.SECONDS)
            .initialMaxData(4611686018427387903L)
            .initialMaxStreamDataBidirectionalRemote(1250000L)
            .initialMaxStreamDataBidirectionalLocal(1250000L)
            .initialMaxStreamDataUnidirectional(1250000L)
            .build();
         new Bootstrap()
            .group(this.group)
            .channel(datagramChannelClass(this.group))
            .handler(codec)
            .bind(0)
            .addListener((ChannelFuture datagramFuture) -> {
               if (!datagramFuture.isSuccess()) {
                  this.markUnhealthy();
                  return;
               }

               this.datagramChannel = (DatagramChannel) datagramFuture.channel();
               QuicChannel.newBootstrap(this.datagramChannel)
                  .streamHandler(this.childHandler)
                  .handler(new ChannelInboundHandlerAdapter() {
                     @Override
                     public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        E4mcEscapeSession.this.markUnhealthy();
                     }

                     @Override
                     public void channelInactive(ChannelHandlerContext ctx) {
                        E4mcEscapeSession.this.state = State.STOPPED;
                     }
                  })
                  .streamOption(ChannelOption.AUTO_READ, false)
                  .remoteAddress(new InetSocketAddress(InetAddress.getByName(relay.host), relay.port))
                  .connect()
                  .addListener(quicFuture -> {
                     if (!quicFuture.isSuccess()) {
                        this.markUnhealthy();
                        return;
                     }

                     this.quicChannel = (QuicChannel) quicFuture.getNow();
                     this.openControlStream();
                     this.quicChannel.closeFuture().addListener(ignored -> this.datagramChannel.close());
                  });
            });
      } catch (Throwable e) {
         this.markUnhealthy();
      }
   }

   private void openControlStream() {
      this.quicChannel.createStream(
         QuicStreamType.BIDIRECTIONAL,
         new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel ch) {
               ch.pipeline().addLast(
                  new ControlMessageCodec(),
                  new SimpleChannelInboundHandler<ControlMessageCodec.ControlMessage>() {
                     @Override
                     protected void channelRead0(ChannelHandlerContext ctx, ControlMessageCodec.ControlMessage msg) {
                        if (msg instanceof ControlMessageCodec.DomainAssignmentComplete) {
                           E4mcEscapeSession.this.state = State.STARTED;
                           String domain = ((ControlMessageCodec.DomainAssignmentComplete) msg).domain;
                           if (E4mcEscapeSession.this.onDomainAssigned != null) {
                              E4mcEscapeSession.this.onDomainAssigned.accept(domain);
                           }
                        } else if (msg instanceof ControlMessageCodec.MessageBroadcast) {
                           if (E4mcEscapeSession.this.onBroadcast != null) {
                              E4mcEscapeSession.this.onBroadcast.accept(((ControlMessageCodec.MessageBroadcast) msg).message);
                           }
                        }
                     }
                  }
               );
            }
         }
      ).addListener(it -> {
         if (!it.isSuccess()) {
            this.markUnhealthy();
            return;
         }

         QuicStreamChannel stream = (QuicStreamChannel) it.getNow();
         stream.writeAndFlush(new ControlMessageCodec.RequestDomainAssignment());
      });
   }

   private synchronized void markUnhealthy() {
      if (this.state != State.STOPPING && this.state != State.STOPPED) {
         this.state = State.UNHEALTHY;
      }
   }

   public void stop() {
      this.state = State.STOPPING;
      if (this.quicChannel != null) {
         this.quicChannel.close().addListener(it -> {
            if (this.datagramChannel != null) {
               this.datagramChannel.close().addListener(ignored -> this.state = State.STOPPED);
            } else {
               this.state = State.STOPPED;
            }
         });
      } else if (this.datagramChannel != null) {
         this.datagramChannel.close();
         this.state = State.STOPPED;
      } else {
         this.state = State.STOPPED;
      }
   }
}
