package com.comphenix.packetwrapper;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import com.comphenix.packetwrapper.util.Removed;
import com.comphenix.packetwrapper.utils.ItemFactoryDelegate;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.reflect.FieldUtils;
import com.comphenix.protocol.utility.Constants;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.utility.MinecraftVersion;
import com.google.common.base.CaseFormat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.minecraft.server.v1_15_R1.DispenserRegistry;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

public class TestWrappers {

	@BeforeClass
	public static void beforeClass() {
		MinecraftReflection.setMinecraftPackage(Constants.NMS, Constants.OBC);
		MinecraftVersion.setCurrentVersion(Constants.CURRENT_VERSION);

		DispenserRegistry.init();

		// Mock the server object
		Server mockedServer = mock(Server.class);
		ItemMeta mockedMeta = mock(ItemMeta.class);
		ItemFactory mockedFactory = new ItemFactoryDelegate(mockedMeta);

		when(mockedServer.getItemFactory()).thenReturn(mockedFactory);
		when(mockedServer.isPrimaryThread()).thenReturn(true);
		// when(mockedFactory.getItemMeta(any(Material.class))).thenReturn(mockedMeta);

		// Inject this fake server
		try {
			FieldUtils.writeStaticField(Bukkit.class, "server", mockedServer, true);
		} catch (Exception ex) {
			throw new RuntimeException("Exception setting Bukkit.server", ex);
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testWrappers() throws MalformedURLException, ClassNotFoundException, ReflectiveOperationException {
		File classFolder = new File("target/classes");
		File wrappersFolder = new File(classFolder, "com/comphenix/packetwrapper");

		URL[] urls = new URL[] { classFolder.toURI().toURL() };

		ClassLoader cl = new URLClassLoader(urls);

		int failures = 0;
		List<PacketType> types = new ArrayList<>();

		for (String wrapper : wrappersFolder.list()) {
			if (!wrapper.startsWith("Wrapper") || wrapper.contains("$")) {
				continue;
			}

			Class<? extends AbstractPacket> clazz = (Class<? extends AbstractPacket>)
					cl.loadClass("com.comphenix.packetwrapper." + wrapper.replace(".class", ""));

			if (clazz.getAnnotation(Deprecated.class) != null) {
				System.out.println("Skipping deprecated wrapper " + clazz.getSimpleName());
				continue;
			}

			System.out.println("> " + clazz.getSimpleName());

			Constructor<? extends AbstractPacket> ctor = clazz.getConstructor();
			AbstractPacket instance = ctor.newInstance();

			PacketType type = instance.handle.getType();
			types.add(type);

			if (type == PacketType.Play.Server.COMBAT_EVENT) {
				// this one's too dependent on state to test right now
				continue;
			}

			for (Method method : clazz.getMethods()) {
				try {
					if (method.getAnnotation(Removed.class) != null) {
						continue;
					}

					method.setAccessible(true);
					if (method.getDeclaringClass().equals(clazz) && method.getParameterTypes().length == 0) {
						System.out.println("Invoking " + method.getName());
						if (method.getName().equals("getContents")) {
							clazz.getMethod("setContentsBuffer", ByteBuf.class).invoke(instance, Unpooled.buffer());
						}

						method.invoke(instance);

						if (method.getName().equalsIgnoreCase("getEntityID")) {
							if (method.getName().equals("getEntityId")) {
								System.err.println(clazz.getName() + " :: " + method.getName() + " is improperly cased!");
							} else {
								try {
									clazz.getMethod("getEntity", World.class);
								} catch (Throwable ex) {
									System.err.println(clazz.getName() + " does not specify a getEntity(World) method!");
								}
							}
						}
					}
				} catch (Throwable ex) {
					System.err.println("Failed to invoke method " + method + ":");
					ex.printStackTrace();

					// failures.add(clazz.getName() + " :: " + method.getName() + " :: " + ex.getCause());
					failures++;
				}
			}
		}

		for (PacketType type : PacketType.values()) {
			if (! types.contains(type)) {
				if (type.isDeprecated()) {
					System.out.println("Wrapper does not exist for deprecated type: " + type);
				} else if (type.isDynamic()) {
					System.out.println("PacketType does not exist for packet class: " + type.getPacketClass().getSimpleName());
				} else {
					System.err.println("Wrapper does not exist for packet: " + type.toString());
					failures++;
				}
			}
		}

		assertEquals(0, failures);
	}
}
