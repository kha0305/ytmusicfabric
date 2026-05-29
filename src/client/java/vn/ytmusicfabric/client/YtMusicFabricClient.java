package vn.ytmusicfabric.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import vn.ytmusicfabric.client.gui.YtMusicScreen;
import vn.ytmusicfabric.client.hud.YtMusicHudOverlay;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class YtMusicFabricClient implements ClientModInitializer {
	private static final YtMusicController CONTROLLER = new YtMusicController(MinecraftClient::getInstance);
	private static final String OPEN_GUI_KEY_TRANSLATION = "key.ytmusicfabric.open_screen";
	private static final String OPEN_GUI_KEY_CATEGORY = "key.categories.ytmusicfabric.general";
	private static KeyBinding openGuiKeyBinding;

	@Override
	public void onInitializeClient() {
		openGuiKeyBinding = KeyBindingHelper.registerKeyBinding(createOpenGuiKeyBinding());

		YtMusicHudOverlay.register(CONTROLLER);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> CONTROLLER.shutdown());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CONTROLLER.stopSilently());
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			CONTROLLER.onClientTick(client);
			while (openGuiKeyBinding.wasPressed()) {
				toggleScreen(client);
			}
		});
	}

	private static KeyBinding createOpenGuiKeyBinding() {
		ReflectiveOperationException lastError = null;

		for (Constructor<?> constructor : KeyBinding.class.getConstructors()) {
			Class<?>[] parameterTypes = constructor.getParameterTypes();
			if (parameterTypes.length != 4
				|| parameterTypes[0] != String.class
				|| parameterTypes[1] != InputUtil.Type.class
				|| parameterTypes[2] != int.class) {
				continue;
			}

			try {
				Object category = resolveCategoryArgument(parameterTypes[3]);
				return (KeyBinding) constructor.newInstance(
					OPEN_GUI_KEY_TRANSLATION,
					InputUtil.Type.KEYSYM,
					GLFW.GLFW_KEY_Y,
					category
				);
			} catch (ReflectiveOperationException exception) {
				lastError = exception;
			}
		}

		throw new IllegalStateException("Khong tao duoc keybind mo GUI tuong thich version hien tai.", lastError);
	}

	private static Object resolveCategoryArgument(Class<?> categoryType) throws ReflectiveOperationException {
		if (categoryType == String.class) {
			return OPEN_GUI_KEY_CATEGORY;
		}

		for (Field field : categoryType.getFields()) {
			if (!Modifier.isStatic(field.getModifiers()) || field.getType() != categoryType) {
				continue;
			}

			Object value = field.get(null);
			if (value != null) {
				return value;
			}
		}

		for (Constructor<?> constructor : categoryType.getDeclaredConstructors()) {
			if (constructor.getParameterCount() == 0) {
				constructor.setAccessible(true);
				return constructor.newInstance();
			}
		}

		throw new NoSuchFieldException("Khong tim thay gia tri category cho keybind.");
	}

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(buildRoot("ytmusic"));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> buildRoot(String rootName) {
		return ClientCommandManager.literal(rootName)
			.executes(context -> {
				CONTROLLER.showHelp();
				return 1;
			})
			.then(ClientCommandManager.literal("play")
				.then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
					.executes(context -> {
						CONTROLLER.play(StringArgumentType.getString(context, "url"));
						return 1;
					})))
			.then(ClientCommandManager.literal("pause")
				.executes(context -> {
					CONTROLLER.pause();
					return 1;
				}))
			.then(ClientCommandManager.literal("resume")
				.executes(context -> {
					CONTROLLER.resume();
					return 1;
				}))
			.then(ClientCommandManager.literal("stop")
				.executes(context -> {
					CONTROLLER.stopWithFeedback();
					return 1;
				}))
			.then(ClientCommandManager.literal("status")
				.executes(context -> {
					CONTROLLER.reportStatus();
					return 1;
				}))
			.then(ClientCommandManager.literal("loop")
				.executes(context -> {
					CONTROLLER.toggleLoop();
					return 1;
				})
				.then(ClientCommandManager.literal("on")
					.executes(context -> {
						CONTROLLER.setLoopEnabled(true);
						return 1;
					}))
				.then(ClientCommandManager.literal("off")
					.executes(context -> {
						CONTROLLER.setLoopEnabled(false);
						return 1;
					})))
			.then(ClientCommandManager.literal("hud")
				.executes(context -> {
					CONTROLLER.toggleHudVisibility();
					return 1;
				})
				.then(ClientCommandManager.literal("on")
					.executes(context -> {
						CONTROLLER.setHudVisible(true);
						return 1;
					}))
				.then(ClientCommandManager.literal("off")
					.executes(context -> {
						CONTROLLER.setHudVisible(false);
						return 1;
					})))
			.then(ClientCommandManager.literal("playlist")
				.executes(context -> {
					CONTROLLER.reportPlaylist();
					return 1;
				})
				.then(ClientCommandManager.literal("show")
					.executes(context -> {
						CONTROLLER.reportPlaylist();
						return 1;
					}))
				.then(ClientCommandManager.literal("clear")
					.executes(context -> {
						CONTROLLER.clearUpcomingPlaylist();
						return 1;
					}))
				.then(ClientCommandManager.literal("code")
					.executes(context -> {
						CONTROLLER.reportPlaylistCode();
						return 1;
					})
					.then(ClientCommandManager.literal("play")
						.then(ClientCommandManager.argument("code", StringArgumentType.greedyString())
							.executes(context -> {
								CONTROLLER.playPlaylistCode(StringArgumentType.getString(context, "code"));
								return 1;
							})))
					.then(ClientCommandManager.literal("import")
						.then(ClientCommandManager.argument("code", StringArgumentType.greedyString())
							.executes(context -> {
								CONTROLLER.playPlaylistCode(StringArgumentType.getString(context, "code"));
								return 1;
							})))
					.then(ClientCommandManager.literal("export")
						.executes(context -> {
							CONTROLLER.reportPlaylistCode();
							return 1;
						}))))
			.then(ClientCommandManager.literal("tools")
				.executes(context -> {
					CONTROLLER.reportToolStatus();
					return 1;
				}))
			.then(ClientCommandManager.literal("volume")
				.executes(context -> {
					CONTROLLER.reportVolume();
					return 1;
				})
				.then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 100))
					.executes(context -> {
						CONTROLLER.setVolumePercent(IntegerArgumentType.getInteger(context, "percent"));
						return 1;
					})))
			.then(ClientCommandManager.literal("install")
				.then(ClientCommandManager.literal("ytdlp")
					.executes(context -> {
						CONTROLLER.installTools(true, false);
						return 1;
					}))
				.then(ClientCommandManager.literal("ffmpeg")
					.executes(context -> {
						CONTROLLER.installTools(false, true);
						return 1;
					}))
				.then(ClientCommandManager.literal("all")
					.executes(context -> {
						CONTROLLER.installTools(true, true);
						return 1;
					})))
			.then(ClientCommandManager.literal("cache")
				.then(ClientCommandManager.literal("clear")
					.executes(context -> {
						CONTROLLER.clearCache();
						return 1;
					})))
			.then(ClientCommandManager.literal("reload")
				.executes(context -> {
					CONTROLLER.softReloadClient();
					return 1;
				}))
			.then(ClientCommandManager.literal("gui")
				.executes(context -> {
					openScreen();
					return 1;
				}));
	}

	private static void toggleScreen(MinecraftClient client) {
		if (client.currentScreen instanceof YtMusicScreen) {
			client.setScreen(null);
			return;
		}

		openScreen();
	}

	private static void openScreen() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return;
		}

		client.setScreen(new YtMusicScreen(CONTROLLER));
	}
}
