package com.minersmovies.server;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.HashMap;
import java.util.zip.GZIPOutputStream;

import javax.annotation.Nullable;

import net.minecraft.server.v1_7_R1.MinecraftServer;
import net.minecraft.server.v1_7_R1.NetworkManager;
import net.minecraft.util.io.netty.buffer.ByteBuf;
import net.minecraft.util.io.netty.buffer.Unpooled;
import net.minecraft.util.io.netty.buffer.UnpooledByteBufAllocator;
import net.minecraft.util.io.netty.buffer.UnpooledHeapByteBuf;
import net.minecraft.util.io.netty.channel.Channel;
import net.minecraft.util.io.netty.channel.ChannelDuplexHandler;
import net.minecraft.util.io.netty.channel.ChannelHandlerContext;
import net.minecraft.util.io.netty.channel.ChannelPromise;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World.Environment;
import org.bukkit.craftbukkit.v1_7_R1.CraftServer;
import org.bukkit.craftbukkit.v1_7_R1.entity.CraftPlayer;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import com.google.common.base.Function;

public class MinersMoviesCapture implements Listener {
	private static Function<Object, Channel> CHANNEL_ACCESSOR = getFieldAccessor(NetworkManager.class, Channel.class, 0);
	
	private boolean closed;
	private Plugin plugin;
	
	private class MonitoredPlayer {
		private GZIPOutputStream stream;
		private long joined;
		private String playerName;
		
		MonitoredPlayer(Player player) throws FileNotFoundException, IOException {
			playerName = player.getPlayerListName();
			joined = new Date().getTime();
			stream = new GZIPOutputStream(new FileOutputStream("recording." + joined + "." + playerName + ".miners"));

			MinecraftServer server = MinecraftServer.getServer();

			int gamemode = player.getGameMode().getValue();
			boolean hardcore = server.isHardcore();
			int dimension = player.getWorld().getEnvironment().getId();
			Location spawn = player.getWorld().getSpawnLocation();
			int heldItem = player.getInventory().getHeldItemSlot();
			int entityId = player.getEntityId();
			int difficulty = player.getWorld().getDifficulty().getValue();
			
//			System.out.println(gamemode);
//			System.out.println(hardcore);
//			System.out.println(dimension);
//			System.out.println(spawn);
//			System.out.println(heldItem);
//			System.out.println(entityId);
//			System.out.println(player);
			
			// Sorry :-)
			String meta = "{" +
				"\"recorded\":" + (joined/1000) + "," +
				"\"player_ign\":\"" + playerName + "\"," +
				"\"source_codec\":\"1.7.2\"" + "," +
				"\"source_format\":\"bukkit\"" +
			"}";
			savePacket('M', Unpooled.wrappedBuffer(meta.getBytes()));
				
			String extra = "{" +
				"\"gamemode\":" + gamemode + "," +
				"\"hardcore\":" + hardcore + "," +
				"\"dimension\":" + dimension + "," +
				"\"spawn\":{\"x\":" + spawn.getX() + ",\"y\":" + spawn.getY() + ",\"z\":" + spawn.getZ() + "}," +
				"\"helditem\":" + heldItem + "," +
				"\"entity_id\":" + entityId + "," +
				"\"difficulty\":" + difficulty +
			"}";
			savePacket('E', Unpooled.wrappedBuffer(extra.getBytes()));
			
			System.out.println("Monitoring " + playerName);
		}
		
		public void savePacket(char side, ByteBuf buf) {
			int size = buf.readableBytes();
			long delta = (new Date().getTime()) - joined;
			String header = String.format("%012d%012d%c", delta, size, side);
			try {
				stream.write(header.getBytes());
				buf.getBytes(0, stream, buf.readableBytes());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		void close() {
			try {
				stream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			System.out.println("Demonitoring " + playerName);
		}
	}
	
    private HashMap<Player, MonitoredPlayer> monitoredPlayers = new HashMap<Player, MonitoredPlayer>();
	
	public MinersMoviesCapture(Plugin plugin) {
		this.plugin = plugin;
		this.plugin.getServer().getPluginManager().registerEvents(this, plugin);
	}
	
	@EventHandler
	public final void onPlayerJoin(PlayerJoinEvent e) {
		if (closed) 
			return;
		monitorPlayer(e.getPlayer());
	}
	
	@EventHandler
	public final void onPlayerQuit(PlayerQuitEvent e) {
		if (closed) 
			return;
		demonitor(e.getPlayer());
	}
	
	private void monitorPlayer(final Player player) {
		final MonitoredPlayer monitoredPlayer;
		try {
			monitoredPlayer = new MonitoredPlayer(player);
		} catch (FileNotFoundException e) {
			System.out.println("Cannot log player " + player.getPlayerListName() + ":" + e);
			return;
		} catch (IOException e) {
			System.out.println("Cannot log player " + player.getPlayerListName() + ":" + e);
			return;
		}

		monitoredPlayers.put(player, monitoredPlayer);
		
		// Inject our packet interceptor
		getChannel(player).pipeline().addBefore("splitter", "mm-reader", new ChannelDuplexHandler() {
			@Override
			public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
				monitoredPlayer.savePacket('C', (ByteBuf)msg);
				super.channelRead(ctx, msg);
			}
		});

		getChannel(player).pipeline().addBefore("prepender", "mm-writer", new ChannelDuplexHandler() {
			@Override
			public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
				monitoredPlayer.savePacket('S', (ByteBuf)msg);
				super.write(ctx, msg, promise);
			}
		});
	}
	
	private void demonitor(final Player player) {
		MonitoredPlayer monitoredPlayer = monitoredPlayers.get(player);
		if (monitoredPlayer == null)
			return;
		
		//System.out.println(getChannel(player).pipeline());
		if (getChannel(player).pipeline().get("mm-reader") != null) {
			// Deregister (not needed when player disconnected but during plugin reload).
			getChannel(player).pipeline().remove("mm-reader");
			getChannel(player).pipeline().remove("mm-writer");
		}
		
		monitoredPlayer.close();
		monitoredPlayers.remove(monitoredPlayer);
	}

	@EventHandler
	public final void onPluginDisable(PluginDisableEvent e) {
		if (e.getPlugin().equals(plugin)) {
			close();
		}
	}
	
	private Channel getChannel(Player player) {
		NetworkManager manager = ((CraftPlayer) player.getPlayer()).getHandle().playerConnection.networkManager;
		return CHANNEL_ACCESSOR.apply(manager);
	}
		
	/**
	 * Retrieve a field accessor for a specific field type and index.
	 * @param target - the target type.
	 * @param fieldType - the field type.
	 * @param index - the index.
	 * @return The field accessor.
	 */
	public static <T> Function<Object, T> getFieldAccessor(Class<?> target, Class<T> fieldType, int index) {
		for (Field field : target.getDeclaredFields()) {
			if (fieldType.isAssignableFrom(field.getType()) && index-- <= 0) {
				final Field targetField = field;
				field.setAccessible(true);
				
				// A function for retrieving a specific field value
				return new Function<Object, T>() {
					@SuppressWarnings("unchecked")
					@Override
					public T apply(@Nullable Object instance) {
						try {
							return (T) targetField.get(instance);
						} catch (IllegalAccessException e) {
							throw new RuntimeException("Cannot access reflection.", e);
						}
					}
				};
			}
		}
		
		// Search in parent classes
		if (target.getSuperclass() != null)
			return getFieldAccessor(target.getSuperclass(), fieldType, index);
		throw new IllegalArgumentException("Cannot find field with type " + fieldType);
	}
	
	public final void close() {
		if (!closed) {
			closed = true;
	
			// Remove our handlers
			for (Player player : plugin.getServer().getOnlinePlayers()) {
				demonitor(player);
			}
		}
	}
}
