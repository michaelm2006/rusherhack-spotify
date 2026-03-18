package me.john200410.spotify.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import joptsimple.internal.Strings;
import me.john200410.spotify.SpotifyPlugin;
import me.john200410.spotify.http.SpotifyAPI;
import me.john200410.spotify.http.responses.PlaybackState;
import net.minecraft.Util;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.apache.commons.io.IOUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.rusherhack.client.api.events.client.input.EventMouse;
import org.rusherhack.client.api.feature.hud.ResizeableHudElement;
import org.rusherhack.client.api.render.IRenderer2D;
import org.rusherhack.client.api.render.RenderContext;
import org.rusherhack.client.api.render.font.IFontRenderer;
import org.rusherhack.client.api.ui.ScaledElementBase;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.client.api.utils.InputUtils;
import org.rusherhack.core.event.stage.Stage;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.interfaces.IClickable;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.utils.Timer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;

/**
 * @author John200410
 */
public class SpotifyHudElement extends ResizeableHudElement {
	
	private static final PlaybackState.Item AI_DJ_SONG = new PlaybackState.Item(); //item that is displayed when the DJ is talking
	
	static {
		AI_DJ_SONG.album = new PlaybackState.Item.Album();
		AI_DJ_SONG.artists = new PlaybackState.Item.Artist[1];
		AI_DJ_SONG.album.images = new PlaybackState.Item.Album.Image[1];
		AI_DJ_SONG.artists[0] = new PlaybackState.Item.Artist();
		AI_DJ_SONG.album.images[0] = new PlaybackState.Item.Album.Image();
		
		AI_DJ_SONG.artists[0].name = "Spotify";
		AI_DJ_SONG.album.name = "Songs Made For You";
		AI_DJ_SONG.name = AI_DJ_SONG.id = "DJ";
		AI_DJ_SONG.duration_ms = 30000;
		AI_DJ_SONG.album.images[0].url = "https://i.imgur.com/29vr8jz.png";
		AI_DJ_SONG.album.images[0].width = 640;
		AI_DJ_SONG.album.images[0].height = 640;
		AI_DJ_SONG.uri = "";
	}
	
	/**
	 * Settings
	 */
	private final BooleanSetting authenticateButton = new BooleanSetting("Authenticate", true)
			.setVisibility(() -> !this.isConnected());
	private final NumberSetting<Double> updateDelay = new NumberSetting<>("UpdateDelay", 0.5d, 0.25d, 2d);
	
	/**
	 * Media Controller
	 */
	private final SongInfoHandler songInfo;
	
	/**
	 * Variables
	 */
	private final DynamicTexture trackThumbnailTexture;
	private final SpotifyPlugin plugin;
	private PlaybackState.Item song = null;
	private boolean consumedButtonClick = false;
	
	
	public SpotifyHudElement(SpotifyPlugin plugin) {
		super("Spotify");
		this.plugin = plugin;
		
		this.songInfo = new SongInfoHandler();

		this.trackThumbnailTexture = new DynamicTexture(640, 640, false);
		this.trackThumbnailTexture.setFilter(true, true);
		
		//dummy setting whos only purpose is to be clicked to open the web browser
		this.authenticateButton.onChange((b) -> {
			if(!b) {
				try {
					Util.getPlatform().openUri(new URI("http://localhost:4000/"));
				} catch(URISyntaxException e) {
					this.plugin.getLogger().error(e.getMessage(), e);
				}
				
				this.authenticateButton.setValue(true);
			}
		});
		
		this.registerSettings(authenticateButton, updateDelay);
		
		//dont ask
		//this.setupDummyModuleBecauseImFuckingStupidAndForgotToRegisterHudElementsIntoTheEventBus();
	}
	
	@Override
	public void tick() {
		
		if(!this.isConnected()) {
			return;
		}
		
		final SpotifyAPI api = this.plugin.getAPI();
		api.updateStatus((long) (this.updateDelay.getValue() * 1000));
		
		final PlaybackState status = api.getCurrentStatus();
		
		if(status == null) {
			return;
		}
		
		if(status.currently_playing_type.equals("ad") || status.currently_playing_type.equals("unknown") || status.currently_playing_type.equals("episode") || (status.item != null && status.item.uri.startsWith("spotify:local::"))) {
			return;
		}
		
		if(this.song == null || !this.song.equals(status.item)) {
			if(status.item == null) {
				status.item = AI_DJ_SONG;
			}
			
			this.song = status.item;
			this.songInfo.updateSong(this.song);
			//update texture
			if(this.song.album.images.length > 0) {
				
				//highest resolution thumbnail
				PlaybackState.Item.Album.Image thumbnail = null;
				for(PlaybackState.Item.Album.Image t : this.song.album.images) {
					if(thumbnail == null || (t.width > thumbnail.width && t.height > thumbnail.height)) {
						thumbnail = t;
					}
				}
				
				if(thumbnail == null) {
					this.plugin.getLogger().error("Thumbnail null?");
					return;
				}
				
				final String thumbnailURL = thumbnail.url;
				api.submit(() -> {
					
					InputStream inputStream = null;
					
					try {
						final HttpRequest request = HttpRequest.newBuilder(new URI(thumbnailURL))
															   .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/98.0.4758.102 Safari/537.36")
															   .build();
						
						final HttpResponse<InputStream> response = SpotifyAPI.HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
						inputStream = response.body();
						
						//convert to png
						final BufferedImage bufferedImage = ImageIO.read(inputStream);
						final ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
						ImageIO.write(bufferedImage, "png", imageBytes);
						
						final byte[] byteArray = imageBytes.toByteArray();
						
						final NativeImage nativeImage = readNativeImage(byteArray);
						
						RenderSystem.recordRenderCall(() -> {
							this.trackThumbnailTexture.setPixels(nativeImage);
							this.trackThumbnailTexture.upload();
							this.trackThumbnailTexture.setFilter(true, true);
						});
					} catch(Throwable e) {
						this.trackThumbnailTexture.setPixels(null);
						this.plugin.getLogger().error("Failed to update thumbnail", e);
					} finally {
						IOUtils.closeQuietly(inputStream);
					}
				});
			} else {
				this.trackThumbnailTexture.setPixels(null);
			}
		}
	}
	
	@Override
	public void renderContent(RenderContext context, double mouseX, double mouseY) {
		final IRenderer2D renderer = this.getRenderer();
		final IFontRenderer fr = this.getFontRenderer();
		final SpotifyAPI api = this.plugin.getAPI();
		final PoseStack matrixStack = context.pose();

		if(!this.isConnected()) {
			this.trackThumbnailTexture.setPixels(null);
			fr.drawString("Not authenticated with spotify!", 5, 10, -1);
			fr.drawString("Click the \"Authenticate\" button", 5, 30, -1);
			fr.drawString("in the settings to authenticate.", 5, 40, -1);
			return;
		}
		
		if(!api.isPlaybackAvailable()) {
			this.trackThumbnailTexture.setPixels(null);
			fr.drawString("Playback unavailable!", 5, 10, -1);
			fr.drawString("Open spotify on your device", 5, 30, -1);
			return;
		}
		
		final PlaybackState status = api.getCurrentStatus();
		
		if(status == null) {
			this.trackThumbnailTexture.setPixels(null);
			fr.drawString("No status", 5, 10, -1);
			return;
		}
		
		if(status.currently_playing_type.equals("ad")) {
			this.trackThumbnailTexture.setPixels(null);
			fr.drawString("Ad playing", 5, 10, -1);
			return;
		}
		
		if(status.currently_playing_type.equals("unknown") || (status.item != null && status.item.uri.startsWith("spotify:local::"))) {
			this.trackThumbnailTexture.setPixels(null);
			fr.drawString("Unknown media playing", 5, 10, -1);
			return;
		}
		
		if(status.currently_playing_type.equals("episode")) {
			this.trackThumbnailTexture.setPixels(null);
			fr.drawString("Podcast is playing", 5, 10, -1);
			return;
		}

//		final PlaybackState.Item song = status.item;

//		if(song == null) {
//			this.trackThumbnailTexture.setPixels(null);
//			fr.drawString("No song loaded", 5, 10, -1);
//			return;
//		}
		
		//thumbnail
		if(this.trackThumbnailTexture.getPixels() != null && this.isAvailable()) {
			renderer.drawTextureRectangle(this.trackThumbnailTexture.getId(), 65, 65, 5, 5, 65, 65, 3);
		}
		
		final double leftOffset = 75;
		
		//set correct mouse pos because its set to -1, -1 when not in hud editor
		if(!mc.mouseHandler.isMouseGrabbed()) {
			mouseX = (int) InputUtils.getMouseX();
			mouseY = (int) InputUtils.getMouseY();
		}
		
		/////////////////////////////////////////////////////////////////////
		//top
		/////////////////////////////////////////////////////////////////////
		double topOffset = 5;
		
		//song details
		this.songInfo.setX(leftOffset);
		this.songInfo.setY(topOffset);
		this.songInfo.render(renderer, context, mouseX, mouseY, status);
		topOffset += this.songInfo.getHeight();
		
		/////////////////////////////////////////////////////////////////////
		
	}
	
	// clicking on the buttons while in chat
	@Subscribe(stage = Stage.PRE)
	private void onMouseClick(EventMouse.Key event) {
		if(event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			return;
		}
		if(!(mc.screen instanceof ChatScreen)) {
			return;
		}
		
		final double mouseX = event.getMouseX();
		final double mouseY = event.getMouseY();
		
		final double x = this.getStartX();
		final double y = this.getStartY();
		
		if(mouseX >= x && mouseX <= x + this.getScaledWidth() && mouseY >= y && mouseY <= y + this.getScaledHeight() && event.getAction() == GLFW.GLFW_PRESS) {
			this.consumedButtonClick = true;
			mouseClicked(mouseX, mouseY, event.getButton());
			this.consumedButtonClick = false;
		} else if(event.getAction() == GLFW.GLFW_RELEASE) {
			mouseReleased(mouseX, mouseY, event.getButton());
		}
	}
	
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if(this.consumedButtonClick) {
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}
	
	@Override
	public double getWidth() {
		return 225;
	}
	
	@Override
	public double getHeight() {
		return 75;
	}
	
	@Override
	public boolean shouldDrawBackground() {
		return false;
	}
	
	private boolean isConnected() {
		return this.plugin.getAPI() != null && this.plugin.getAPI().isConnected();
	}
	
	private boolean isAvailable() {
		return this.isConnected() && this.plugin.getAPI().isPlaybackAvailable();
	}
	
	private NativeImage readNativeImage(byte[] bytes) throws IOException {
		MemoryStack memoryStack = MemoryStack.stackGet();
		int i = memoryStack.getPointer();
		if (i < bytes.length) {
			ByteBuffer byteBuffer = MemoryUtil.memAlloc(bytes.length);
			
			NativeImage nativeImage;
			try {
				byteBuffer.put(bytes);
				byteBuffer.rewind();
				nativeImage = NativeImage.read(byteBuffer);
			} finally {
				MemoryUtil.memFree(byteBuffer);
			}
			
			return nativeImage;
		} else {
			try(MemoryStack memoryStack2 = MemoryStack.stackPush()) {
				ByteBuffer byteBuffer2 = memoryStack2.malloc(bytes.length);
				byteBuffer2.put(bytes);
				byteBuffer2.rewind();
				return NativeImage.read(byteBuffer2);
			}
		}
	}
	
	abstract class ElementHandler extends ScaledElementBase implements IClickable {
		
		abstract void render(IRenderer2D renderer, RenderContext context, double mouseX, double mouseY, PlaybackState status);
		
		@Override
		public double getWidth() {
			return SpotifyHudElement.this.getWidth() - 75 - 5;
		}
		
		@Override
		public double getScale() {
			return SpotifyHudElement.this.getScale();
		}
		
		public double getScaledX() {
			return this.getX() * this.getScale();
		}
		
		public double getScaledY() {
			return this.getY() * this.getScale();
		}
		
		@Override
		public boolean isHovered(double mouseX, double mouseY) {
			mouseX -= SpotifyHudElement.this.getStartX();
			mouseY -= SpotifyHudElement.this.getStartY();
			
			return mouseX >= this.getScaledX() && mouseX <= this.getScaledX() + this.getScaledWidth() && mouseY >= this.getScaledY() && mouseY <= this.getScaledY() + this.getScaledHeight();
		}
	}
	
	class SongInfoHandler extends ElementHandler {
		
		private final ScrollingText title = new ScrollingText();
		private final ScrollingText artists = new ScrollingText();
		private final ScrollingText album = new ScrollingText();
		
		@Override
		void render(IRenderer2D renderer, RenderContext context, double mouseX, double mouseY, PlaybackState status) {
			final IFontRenderer fr = SpotifyHudElement.this.getFontRenderer();
			final PoseStack matrixStack = context.pose();
			
			matrixStack.pushPose();
			matrixStack.translate(this.getX(), this.getY(), 0);
			renderer.scissorBox(0, 0, this.getWidth(), this.getHeight());

			final double titleMaxWidth = this.getWidth();
			renderer.scissorBox(0, -1, titleMaxWidth, this.getHeight());
			this.title.render(context, renderer, fr, titleMaxWidth, -1);
			
			matrixStack.translate(0, fr.getFontHeight() + 1, 0);
			matrixStack.scale(0.75f, 0.75f, 1);

			this.artists.render(context, renderer, fr, titleMaxWidth / 0.75, Color.LIGHT_GRAY.getRGB());
			
			renderer.popScissorBox();
			
			matrixStack.translate(0, fr.getFontHeight() + 1, 0);
			
			this.album.render(context, renderer, fr, this.getWidth() / 0.75, Color.LIGHT_GRAY.getRGB());
			
			renderer.popScissorBox();
			matrixStack.popPose();
		}
		
		@Override
		public double getHeight() {
			return (getFontRenderer().getFontHeight() + 1) * 3;
		}
		
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			return false;
		}
		
		public void updateSong(PlaybackState.Item song) {
			this.title.setText(song.name);
			
			final String[] artists = new String[song.artists.length];
			for(int i = 0; i < song.artists.length; i++) {
				artists[i] = song.artists[i].name;
			}
			
			this.artists.setText("by " + Strings.join(artists, ", "));
			this.album.setText("on " + song.album.name);
		}
		
		static class ScrollingText {
			
			private String text;
			private double scroll = 0;
			private boolean scrolling = false;
			private boolean scrollingForward = false;
			private final Timer pauseTimer = new Timer();
			private long lastUpdate = 0;
			
			void render(RenderContext context, IRenderer2D renderer, IFontRenderer fr, double width, int color) {
				if(this.text == null) {
					return;
				}
				
				final double textWidth = fr.getStringWidth(this.text);
				final double maxScroll = textWidth - width;
				
				if(maxScroll <= 0) {
					fr.drawString(this.text, 0, 0, color);
					return;
				}
				
				if(this.scrolling) {
					this.pauseTimer.reset();
					
					if(this.scrollingForward) {
						this.scroll += (System.currentTimeMillis() - this.lastUpdate) / 75f;
						
						if(this.scroll >= maxScroll) {
							this.scroll = maxScroll;
							this.scrolling = false;
						}
					} else {
						this.scroll -= (System.currentTimeMillis() - this.lastUpdate) / 75f;
						
						if(this.scroll <= 0) {
							this.scroll = 0;
							this.scrolling = false;
						}
					}
				} else {
					if(this.pauseTimer.passed(2500)) {
						this.scrolling = true;
						this.scrollingForward = !this.scrollingForward;
					}
				}
				
				fr.drawString(this.text, -this.scroll, 0, color);
				this.lastUpdate = System.currentTimeMillis();
			}
			
			void setText(String text) {
				this.text = text;
				this.scroll = 0;
				this.pauseTimer.reset();
				this.scrolling = false;
				this.scrollingForward = false;
			}
		}
	}
	
}