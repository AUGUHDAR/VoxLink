package icu.wuhui.voxlink.mixin;

import icu.wuhui.voxlink.e4mc.E4mcEscapeManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetAddress;

/**
 * 捕获 MC 自建服务端的 childHandler/group 供逃生通道复用。
 * 模式对齐 e4mc (MIT, vgskye) ServerConnectionListenerMixin 6.x。
 */
@Mixin(ServerConnectionListener.class)
public abstract class E4mcServerConnectionMixin {
   @Unique
   private ChannelHandler voxlink$capturedChildHandler;
   @Unique
   private EventLoopGroup voxlink$capturedGroup;

   @ModifyArg(
      method = "startTcpServerListener",
      at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;childHandler(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/ServerBootstrap;", remap = false)
   )
   private ChannelHandler voxlink$interceptChildHandler(ChannelHandler childHandler) {
      this.voxlink$capturedChildHandler = childHandler;
      return childHandler;
   }

   @ModifyArg(
      method = "startTcpServerListener",
      at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/ServerBootstrap;", remap = false)
   )
   private EventLoopGroup voxlink$interceptGroup(EventLoopGroup group) {
      this.voxlink$capturedGroup = group;
      return group;
   }

   @Inject(method = "startTcpServerListener", at = @At("TAIL"))
   private void voxlink$onServerStarted(InetAddress address, int port, CallbackInfo ci) {
      E4mcEscapeManager.onServerChannelReady(this.voxlink$capturedChildHandler, this.voxlink$capturedGroup);
   }

   @Inject(method = "stop", at = @At("HEAD"))
   private void voxlink$onServerStop(CallbackInfo ci) {
      E4mcEscapeManager.onServerStopped();
   }
}
