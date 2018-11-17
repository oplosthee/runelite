/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.gpu;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Provides;
import com.jogamp.nativewindow.AbstractGraphicsConfiguration;
import com.jogamp.nativewindow.NativeWindowFactory;
import com.jogamp.nativewindow.awt.AWTGraphicsConfiguration;
import com.jogamp.nativewindow.awt.JAWTWindow;
import com.jogamp.opengl.GL;
import static com.jogamp.opengl.GL2ES2.GL_DEBUG_OUTPUT;
import static com.jogamp.opengl.GL3ES3.GL_SHADER_STORAGE_BARRIER_BIT;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLContext;
import com.jogamp.opengl.GLDrawable;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLException;
import com.jogamp.opengl.GLProfile;
import java.awt.Canvas;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.function.Function;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.NodeCache;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import static net.runelite.client.plugins.gpu.GLUtil.glDeleteBuffer;
import static net.runelite.client.plugins.gpu.GLUtil.glDeleteTexture;
import static net.runelite.client.plugins.gpu.GLUtil.glDeleteVertexArrays;
import static net.runelite.client.plugins.gpu.GLUtil.glGenBuffers;
import static net.runelite.client.plugins.gpu.GLUtil.glGenTexture;
import static net.runelite.client.plugins.gpu.GLUtil.glGenVertexArrays;
import static net.runelite.client.plugins.gpu.GLUtil.inputStreamToString;
import net.runelite.client.plugins.gpu.template.Template;
import net.runelite.client.ui.DrawManager;

@PluginDescriptor(
	name = "GPU",
	description = "Utilizes the GPU",
	enabledByDefault = false
)
@Slf4j
public class GpuPlugin extends Plugin implements DrawCallbacks
{
	// This is the maximum number of triangles the compute shaders support
	private static final int MAX_TRIANGLE = 4096;
	private static final int SMALL_TRIANGLE_COUNT = 512;
	private static final int FLAG_SCENE_BUFFER = Integer.MIN_VALUE;
	private static final int MAX_DISTANCE = 90;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GpuPluginConfig config;

	@Inject
	private TextureManager textureManager;

	@Inject
	private SceneUploader sceneUploader;

	@Inject
	private DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	private Canvas canvas;
	private JAWTWindow jawtWindow;
	private GL4 gl;
	private GLDrawable glDrawable;

	private int glProgram;
	private int glVertexShader;
	private int glGeomShader;
	private int glFragmentShader;

	private int glComputeProgram;
	private int glComputeShader;

	private int glSmallComputeProgram;
	private int glSmallComputeShader;

	private int vaoHandle;

	private int interfaceTexture;

	private int glUiProgram;
	private int glUiVertexShader;
	private int glUiFragmentShader;

	private int vaoUiHandle;
	private int vboUiHandle;

	// scene vertex buffer id
	private int bufferId;
	// scene uv buffer id
	private int uvBufferId;

	private int textureArrayId;

	private final IntBuffer uniformBuffer = GpuIntBuffer.allocateDirect(5);
	private final float[] textureOffsets = new float[128];

	private GpuIntBuffer vertexBuffer;
	private GpuFloatBuffer uvBuffer;

	private GpuIntBuffer modelBufferSmall;
	private GpuIntBuffer modelBuffer;

	/**
	 * number of models in small buffer
	 */
	private int smallModels;

	/**
	 * number of models in large buffer
	 */
	private int largeModels;

	/**
	 * offset in the target buffer for model
	 */
	private int targetBufferOffset;

	/**
	 * offset into the temporary scene vertex buffer
	 */
	private int tempOffset;

	/**
	 * offset into the temporary scene uv buffer
	 */
	private int tempUvOffset;

	private int lastViewportWidth;
	private int lastViewportHeight;
	private int lastCanvasWidth;
	private int lastCanvasHeight;

	private int centerX;
	private int centerY;

	// Uniforms
	private int uniFogColor;
	private int uniFogToggle;
	private int uniProjectionMatrix;
	private int uniBrightness;
	private int uniTex;
	private int uniTextures;
	private int uniTextureOffsets;
	private int uniBlockSmall;
	private int uniBlockLarge;
	private int uniBlockMain;

	private static final int SCALE = 2;
	private static final int LOCAL_SCALE = Perspective.LOCAL_TILE_SIZE * SCALE;

	private BufferedImage skybox = null;
	private static Point shift;

	@Override
	protected void startUp()
	{
		clientThread.invoke(() ->
		{
			try
			{
				bufferId = uvBufferId = -1;

				vertexBuffer = new GpuIntBuffer();
				uvBuffer = new GpuFloatBuffer();
				modelBufferSmall = new GpuIntBuffer();
				modelBuffer = new GpuIntBuffer();

				canvas = client.getCanvas();
				canvas.setIgnoreRepaint(true);

				if (log.isDebugEnabled())
				{
					System.setProperty("jogl.debug", "true");
				}

				GLProfile.initSingleton();

				GLProfile glProfile = GLProfile.get(GLProfile.GL4bc);

				GLCapabilities glCaps = new GLCapabilities(glProfile);
				AbstractGraphicsConfiguration config = AWTGraphicsConfiguration.create(canvas.getGraphicsConfiguration(),
					glCaps, glCaps);

				jawtWindow = (JAWTWindow) NativeWindowFactory.getNativeWindow(canvas, config);

				GLDrawableFactory glDrawableFactory = GLDrawableFactory.getFactory(glProfile);

				glDrawable = glDrawableFactory.createGLDrawable(jawtWindow);
				glDrawable.setRealized(true);

				GLContext glContext = glDrawable.createContext(null);
				int res = glContext.makeCurrent();
				if (res == GLContext.CONTEXT_NOT_CURRENT)
				{
					throw new GLException("Unable to make context current");
				}

				this.gl = glContext.getGL().getGL4();
				gl.setSwapInterval(0);

				if (log.isDebugEnabled())
				{
					glContext.enableGLDebugMessage(true);
					gl.glEnable(GL_DEBUG_OUTPUT);
				}

				initVao();
				initProgram();
				initInterfaceTexture();
				initSkybox();

				client.setDrawCallbacks(this);
				client.setGpu(true);

				// force rebuild of main buffer provider to enable alpha channel
				client.resizeCanvas();

				lastViewportWidth = lastViewportHeight = lastCanvasWidth = lastCanvasHeight = -1;

				textureArrayId = -1;

				// increase size of model cache for dynamic objects since we are extending scene size
				NodeCache cachedModels2 = client.getCachedModels2();
				cachedModels2.setCapacity(256);
				cachedModels2.setRemainingCapacity(256);
				cachedModels2.reset();

				if (client.getGameState() == GameState.LOGGED_IN)
				{
					uploadScene();
				}
			}
			catch (Throwable e)
			{
				log.error("Error starting GPU plugin", e);

				try
				{
					pluginManager.setPluginEnabled(this, false);
					pluginManager.stopPlugin(this);
				}
				catch (PluginInstantiationException ex)
				{
					log.error("error stopping plugin", ex);
				}

				shutDown();
			}

		});
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(() ->
		{
			if (textureArrayId != -1)
			{
				textureManager.freeTextureArray(gl, textureArrayId);
				textureArrayId = -1;
			}

			client.setGpu(false);
			client.setDrawCallbacks(null);

			if (bufferId != -1)
			{
				GLUtil.glDeleteBuffer(gl, bufferId);
				bufferId = -1;
			}

			if (uvBufferId != -1)
			{
				GLUtil.glDeleteBuffer(gl, uvBufferId);
				uvBufferId = -1;
			}

			shutdownInterfaceTexture();
			shutdownProgram();
			shutdownVao();

			vertexBuffer = null;
			uvBuffer = null;
			modelBufferSmall = null;
			modelBuffer = null;

			// force main buffer provider rebuild to turn off alpha channel
			client.resizeCanvas();
		});
	}

	@Provides
	GpuPluginConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GpuPluginConfig.class);
	}

	private void initProgram() throws ShaderException
	{
		glProgram = gl.glCreateProgram();
		glVertexShader = gl.glCreateShader(gl.GL_VERTEX_SHADER);
		glGeomShader = gl.glCreateShader(gl.GL_GEOMETRY_SHADER);
		glFragmentShader = gl.glCreateShader(gl.GL_FRAGMENT_SHADER);

		Function<String, String> resourceLoader = (s) -> inputStreamToString(getClass().getResourceAsStream(s));
		Template template = new Template(resourceLoader);
		String source = template.process(resourceLoader.apply("geom.glsl"));

		GLUtil.loadShaders(gl, glProgram, glVertexShader, glGeomShader, glFragmentShader,
			inputStreamToString(getClass().getResourceAsStream("vert.glsl")),
			source,
			inputStreamToString(getClass().getResourceAsStream("frag.glsl")));

		glComputeProgram = gl.glCreateProgram();
		glComputeShader = gl.glCreateShader(gl.GL_COMPUTE_SHADER);
		template = new Template(resourceLoader);
		source = template.process(resourceLoader.apply("comp.glsl"));
		GLUtil.loadComputeShader(gl, glComputeProgram, glComputeShader, source);

		glSmallComputeProgram = gl.glCreateProgram();
		glSmallComputeShader = gl.glCreateShader(gl.GL_COMPUTE_SHADER);
		template = new Template(resourceLoader);
		source = template.process(resourceLoader.apply("comp_small.glsl"));
		GLUtil.loadComputeShader(gl, glSmallComputeProgram, glSmallComputeShader, source);

		glUiProgram = gl.glCreateProgram();
		glUiVertexShader = gl.glCreateShader(gl.GL_VERTEX_SHADER);
		glUiFragmentShader = gl.glCreateShader(gl.GL_FRAGMENT_SHADER);
		GLUtil.loadShaders(gl, glUiProgram, glUiVertexShader, -1, glUiFragmentShader,
			inputStreamToString(getClass().getResourceAsStream("vertui.glsl")),
			null,
			inputStreamToString(getClass().getResourceAsStream("fragui.glsl")));

		initUniforms();
	}

	private void initUniforms()
	{
		uniProjectionMatrix = gl.glGetUniformLocation(glProgram, "projectionMatrix");
		uniBrightness = gl.glGetUniformLocation(glProgram, "brightness");

		uniFogColor = gl.glGetUniformLocation(glProgram, "fogColor");
		uniFogToggle = gl.glGetUniformLocation(glProgram, "fogToggle");

		uniTex = gl.glGetUniformLocation(glUiProgram, "tex");
		uniTextures = gl.glGetUniformLocation(glProgram, "textures");
		uniTextureOffsets = gl.glGetUniformLocation(glProgram, "textureOffsets");

		uniBlockSmall = gl.glGetUniformBlockIndex(glSmallComputeProgram, "uniforms");
		uniBlockLarge = gl.glGetUniformBlockIndex(glComputeProgram, "uniforms");
		uniBlockMain = gl.glGetUniformBlockIndex(glProgram, "uniforms");
	}

	private void shutdownProgram()
	{
		gl.glDeleteShader(glVertexShader);
		glVertexShader = -1;

		gl.glDeleteShader(glGeomShader);
		glGeomShader = -1;

		gl.glDeleteShader(glFragmentShader);
		glFragmentShader = -1;

		gl.glDeleteProgram(glProgram);
		glProgram = -1;

		///

		gl.glDeleteShader(glComputeShader);
		glComputeShader = -1;

		gl.glDeleteProgram(glComputeProgram);
		glComputeProgram = -1;

		gl.glDeleteShader(glSmallComputeShader);
		glSmallComputeShader = -1;

		gl.glDeleteProgram(glSmallComputeProgram);
		glSmallComputeProgram = -1;

		///

		gl.glDeleteShader(glUiVertexShader);
		glUiVertexShader = -1;

		gl.glDeleteShader(glUiFragmentShader);
		glUiFragmentShader = -1;

		gl.glDeleteProgram(glUiProgram);
		glUiProgram = -1;
	}

	private void initVao()
	{
		// Create VAO
		vaoHandle = glGenVertexArrays(gl);

		// Create UI VAO
		vaoUiHandle = glGenVertexArrays(gl);
		// Create UI buffer
		vboUiHandle = glGenBuffers(gl);
		gl.glBindVertexArray(vaoUiHandle);

		FloatBuffer vboUiBuf = GpuFloatBuffer.allocateDirect(5 * 4);
		vboUiBuf.put(new float[]{
			// positions     // texture coords
			1f, 1f, 0.0f, 1.0f, 0f, // top right
			1f, -1f, 0.0f, 1.0f, 1f, // bottom right
			-1f, -1f, 0.0f, 0.0f, 1f, // bottom left
			-1f, 1f, 0.0f, 0.0f, 0f  // top left
		});
		vboUiBuf.rewind();
		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, vboUiHandle);
		gl.glBufferData(gl.GL_ARRAY_BUFFER, vboUiBuf.capacity() * Float.BYTES, vboUiBuf, gl.GL_STATIC_DRAW);

		// position attribute
		gl.glVertexAttribPointer(0, 3, gl.GL_FLOAT, false, 5 * Float.BYTES, 0);
		gl.glEnableVertexAttribArray(0);

		// texture coord attribute
		gl.glVertexAttribPointer(1, 2, gl.GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
		gl.glEnableVertexAttribArray(1);

		// unbind VBO
		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, 0);
	}

	private void shutdownVao()
	{
		glDeleteVertexArrays(gl, vaoHandle);
		vaoHandle = -1;

		glDeleteBuffer(gl, vboUiHandle);
		vboUiHandle = -1;

		glDeleteVertexArrays(gl, vaoUiHandle);
		vaoUiHandle = -1;
	}

	private void initInterfaceTexture()
	{
		interfaceTexture = glGenTexture(gl);
		gl.glBindTexture(gl.GL_TEXTURE_2D, interfaceTexture);
		gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_WRAP_S, gl.GL_REPEAT);
		gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_WRAP_T, gl.GL_REPEAT);
		gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MIN_FILTER, gl.GL_LINEAR);
		gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MAG_FILTER, gl.GL_LINEAR);
		gl.glBindTexture(gl.GL_TEXTURE_2D, 0);
	}

	private void initSkybox()
	{
		synchronized (ImageIO.class)
		{
			try
			{
				skybox = ImageIO.read(getClass().getResourceAsStream("skybox/skybox.png"));
			}
			catch (IOException e)
			{
				throw new RuntimeException(e);
			}
		}

		Point wp = new Point(3232, 3232);
		Point px = new Point(1040, 3600);

		shift = new Point(px.getX() - (wp.getX() / 2), (skybox.getHeight() - px.getY()) - (wp.getY() / 2));
	}

	private void shutdownInterfaceTexture()
	{
		glDeleteTexture(gl, interfaceTexture);
		interfaceTexture = -1;
	}

	private void createProjectionMatrix(float left, float right, float bottom, float top, float near, float far)
	{
		// create a standard orthographic projection
		float tx = -((right + left) / (right - left));
		float ty = -((top + bottom) / (top - bottom));
		float tz = -((far + near) / (far - near));

		gl.glUseProgram(glProgram);

		float[] matrix = new float[]{
			2 / (right - left), 0, 0, 0,
			0, 2 / (top - bottom), 0, 0,
			0, 0, -2 / (far - near), 0,
			tx, ty, tz, 1
		};
		gl.glUniformMatrix4fv(uniProjectionMatrix, 1, false, matrix, 0);

		gl.glUseProgram(0);
	}

	@Override
	public void drawScene(int cameraX, int cameraY, int cameraZ, int cameraPitch, int cameraYaw, int plane)
	{
		centerX = client.getCenterX();
		centerY = client.getCenterY();

		final Scene scene = client.getScene();
		final int drawDistance = Math.max(0, Math.min(MAX_DISTANCE, config.drawDistance()));
		scene.setDrawDistance(drawDistance);
	}


	public void drawScenePaint(int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z,
							SceneTilePaint paint, int tileZ, int tileX, int tileY,
							int zoom, int centerX, int centerY)
	{
		if (paint.getBufferLen() > 0)
		{
			x = tileX * Perspective.LOCAL_TILE_SIZE;
			y = 0;
			z = tileY * Perspective.LOCAL_TILE_SIZE;

			x -= client.getCameraX2();
			y -= client.getCameraY2();
			z -= client.getCameraZ2();

			GpuIntBuffer b = bufferForTriangles(2);

			b.ensureCapacity(8);
			IntBuffer buffer = b.getBuffer();
			buffer.put(paint.getBufferOffset());
			buffer.put(paint.getUvBufferOffset());
			buffer.put(2);
			buffer.put(targetBufferOffset);
			buffer.put(FLAG_SCENE_BUFFER);
			buffer.put(x).put(y).put(z);

			targetBufferOffset += 2 * 3;
		}
	}

	public void drawSceneModel(int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z,
							SceneTileModel model, int tileZ, int tileX, int tileY,
							int zoom, int centerX, int centerY)
	{
		if (model.getBufferLen() > 0)
		{
			x = tileX * Perspective.LOCAL_TILE_SIZE;
			y = 0;
			z = tileY * Perspective.LOCAL_TILE_SIZE;

			x -= client.getCameraX2();
			y -= client.getCameraY2();
			z -= client.getCameraZ2();

			GpuIntBuffer b = bufferForTriangles(model.getBufferLen() / 3);

			b.ensureCapacity(8);
			IntBuffer buffer = b.getBuffer();
			buffer.put(model.getBufferOffset());
			buffer.put(model.getUvBufferOffset());
			buffer.put(model.getBufferLen() / 3);
			buffer.put(targetBufferOffset);
			buffer.put(FLAG_SCENE_BUFFER);
			buffer.put(x).put(y).put(z);

			targetBufferOffset += model.getBufferLen();
		}
	}

	@Override
	public void draw()
	{
		if (jawtWindow.getAWTComponent() != client.getCanvas())
		{
			// We inject code in the game engine mixin to prevent the client from doing canvas replacement,
			// so this should not ever be hit
			log.warn("Canvas invalidated!");
			shutDown();
			startUp();
			return;
		}

		if (client.getGameState() == GameState.LOADING || client.getGameState() == GameState.HOPPING)
		{
			// While the client is loading it doesn't draw
			return;
		}

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		final int viewportHeight = client.getViewportHeight();
		final int viewportWidth = client.getViewportWidth();

		gl.glClear(gl.GL_COLOR_BUFFER_BIT);

		// If the viewport has changed, update the projection matrix
		if (viewportWidth > 0 && viewportHeight > 0 && (viewportWidth != lastViewportWidth || viewportHeight != lastViewportHeight))
		{
			createProjectionMatrix(0, viewportWidth, viewportHeight, 0, 0, Constants.SCENE_SIZE * Perspective.LOCAL_TILE_SIZE);
			lastViewportWidth = viewportWidth;
			lastViewportHeight = viewportHeight;
		}

		// Upload buffers
		vertexBuffer.flip();
		uvBuffer.flip();
		modelBuffer.flip();
		modelBufferSmall.flip();

		int bufferId = glGenBuffers(gl); // temporary scene vertex buffer
		int uvBufferId = glGenBuffers(gl); // temporary scene uv buffer
		int modelBufferId = glGenBuffers(gl); // scene model buffer, large
		int modelBufferSmallId = glGenBuffers(gl); // scene model buffer, small

		IntBuffer vertexBuffer = this.vertexBuffer.getBuffer();
		FloatBuffer uvBuffer = this.uvBuffer.getBuffer();
		IntBuffer modelBuffer = this.modelBuffer.getBuffer();
		IntBuffer modelBufferSmall = this.modelBufferSmall.getBuffer();

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, bufferId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER, vertexBuffer.limit() * Integer.BYTES, vertexBuffer, gl.GL_STREAM_DRAW);

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, uvBufferId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER, uvBuffer.limit() * Float.BYTES, uvBuffer, gl.GL_STREAM_DRAW);

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, modelBufferId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER, modelBuffer.limit() * Integer.BYTES, modelBuffer, gl.GL_STREAM_DRAW);

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, modelBufferSmallId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER, modelBufferSmall.limit() * Integer.BYTES, modelBufferSmall, gl.GL_STREAM_DRAW);

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, 0);

		// allocate target vertex buffer for compute shaders
		int outBufferId = glGenBuffers(gl);
		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, outBufferId);

		gl.glBufferData(gl.GL_ARRAY_BUFFER,
			targetBufferOffset * 16, // each vertex is an ivec4, which is 16 bytes
			null,
			gl.GL_STREAM_DRAW);

		// allocate target uv buffer for compute shaders
		int outUvBufferId = glGenBuffers(gl);
		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, outUvBufferId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER,
			targetBufferOffset * 16,
			null,
			gl.GL_STREAM_DRAW);

		// UBO
		int uniformBufferId = glGenBuffers(gl);
		gl.glBindBuffer(gl.GL_UNIFORM_BUFFER, uniformBufferId);
		uniformBuffer.clear();
		uniformBuffer
			.put(client.getCameraYaw())
			.put(client.getCameraPitch())
			.put(centerX)
			.put(centerY)
			.put(client.getScale());
		uniformBuffer.flip();

		gl.glBufferData(gl.GL_UNIFORM_BUFFER, uniformBuffer.limit() * Integer.BYTES, uniformBuffer, gl.GL_STATIC_DRAW);
		gl.glBindBuffer(gl.GL_UNIFORM_BUFFER, 0);

		gl.glUniformBlockBinding(glSmallComputeProgram, uniBlockSmall, 0);
		gl.glUniformBlockBinding(glComputeProgram, uniBlockLarge, 0);

		gl.glBindBufferBase(gl.GL_UNIFORM_BUFFER, 0, uniformBufferId);

		/*
		 * Compute is split into two separate programs 'small' and 'large' to
		 * save on GPU resources. Small will sort <= 512 faces, large will do <= 4096.
		 */

		// small
		gl.glUseProgram(glSmallComputeProgram);

		gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 0, modelBufferSmallId);
		gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 1, this.bufferId);
		gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 2, bufferId);
		gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 3, outBufferId);
		gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 4, outUvBufferId);
		gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 5, this.uvBufferId);
		gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 6, uvBufferId);

		gl.glDispatchCompute(smallModels, 1, 1);

		// large
		gl.glUseProgram(glComputeProgram);

		gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 0, modelBufferId);
		gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 1, this.bufferId);
		gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 2, bufferId);
		gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 3, outBufferId);
		gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 4, outUvBufferId);
		gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 5, this.uvBufferId);
		gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 6, uvBufferId);

		gl.glDispatchCompute(largeModels, 1, 1);

		gl.glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

		// Draw 3d scene
		final TextureProvider textureProvider = client.getTextureProvider();
		if (textureProvider != null)
		{
			if (textureArrayId == -1)
			{
				// lazy init textures as they may not be loaded at plugin start.
				// this will return -1 and retry if not all textures are loaded yet, too.
				textureArrayId = textureManager.initTextureArray(textureProvider, gl);
			}

			final Texture[] textures = textureProvider.getTextures();
			final int heightOff = client.getViewportYOffset();
			final int widthOff = client.getViewportXOffset();

			gl.glViewport(widthOff, canvasHeight - viewportHeight - heightOff, viewportWidth, viewportHeight);

			gl.glUseProgram(glProgram);

			// Brightness happens to also be stored in the texture provider, so we use that
			gl.glUniform1f(uniBrightness, (float) textureProvider.getBrightness());

			for (int id = 0; id < textures.length; ++id)
			{
				Texture texture = textures[id];
				if (texture == null)
				{
					continue;
				}

				textureProvider.load(id); // trips the texture load flag which lets textures animate

				textureOffsets[id * 2] = texture.getU();
				textureOffsets[id * 2 + 1] = texture.getV();
			}

			// Bind uniforms
			gl.glUniformBlockBinding(glProgram, uniBlockMain, 0);
			gl.glUniform1i(uniTextures, 1); // texture sampler array is bound to texture1
			gl.glUniform2fv(uniTextureOffsets, 128, textureOffsets, 0);

			// We just allow the GL to do face culling. Note this requires the priority renderer
			// to have logic to disregard culled faces in the priority depth testing.
			gl.glEnable(gl.GL_CULL_FACE);

			// Enable blending for alpha
			gl.glEnable(gl.GL_BLEND);
			gl.glBlendFunc(gl.GL_SRC_ALPHA, gl.GL_ONE_MINUS_SRC_ALPHA);

			drawSkybox();

			// Draw output of compute shaders
			gl.glBindVertexArray(vaoHandle);

			gl.glEnableVertexAttribArray(0);
			gl.glBindBuffer(gl.GL_ARRAY_BUFFER, outBufferId);
			gl.glVertexAttribIPointer(0, 4, gl.GL_INT, 0, 0);

			gl.glEnableVertexAttribArray(1);
			gl.glBindBuffer(gl.GL_ARRAY_BUFFER, outUvBufferId);
			gl.glVertexAttribPointer(1, 4, gl.GL_FLOAT, false, 0, 0);

			gl.glDrawArrays(gl.GL_TRIANGLES, 0, targetBufferOffset);

			gl.glDisable(gl.GL_BLEND);
			gl.glDisable(gl.GL_CULL_FACE);

			gl.glUseProgram(0);
		}

		glDeleteBuffer(gl, uniformBufferId);

		vertexBuffer.clear();
		uvBuffer.clear();
		modelBuffer.clear();
		modelBufferSmall.clear();

		targetBufferOffset = 0;
		smallModels = largeModels = 0;
		tempOffset = 0;
		tempUvOffset = 0;

		glDeleteBuffer(gl, bufferId);
		glDeleteBuffer(gl, uvBufferId);
		glDeleteBuffer(gl, modelBufferId);
		glDeleteBuffer(gl, modelBufferSmallId);
		glDeleteBuffer(gl, outBufferId);
		glDeleteBuffer(gl, outUvBufferId);

		// Texture on UI
		drawUi(canvasHeight, canvasWidth);

		glDrawable.swapBuffers();

		drawManager.processDrawComplete(this::screenshot);
	}

	private void drawSkybox()
	{
		float[] rgbAvg = new float[3];

		if (skybox == null)
		{
			return;
		}

		if (config.skyboxType() == SkyboxType.SOLID)
		{
			Player player = client.getLocalPlayer();
			if (player == null)
			{
				return;
			}

			LocalPoint lp = player.getLocalLocation();

			// basic bilinear to smooth the values

			int xm = lp.getX();
			int ym = lp.getY();
			int x0 = xm & -LOCAL_SCALE;
			int y0 = ym & -LOCAL_SCALE;
			int x1 = x0 + LOCAL_SCALE;
			int y1 = y0 + LOCAL_SCALE;

			int area = 0;
			area += getColorForTile(x1, y1, xm - x0, ym - y0, rgbAvg);
			area += getColorForTile(x1, y0, xm - x0, y1 - ym, rgbAvg);
			area += getColorForTile(x0, y1, x1 - xm, ym - y0, rgbAvg);
			area += getColorForTile(x0, y0, x1 - xm, y1 - ym, rgbAvg);

			if (area <= 0)
			{
				return;
			}

			area *= 255f;
			rgbAvg[0] /= area;
			rgbAvg[1] /= area;
			rgbAvg[2] /= area;
		}

		// Used as multiplier for the fog distance, thus effectively toggling it off when the setting is disabled.
		gl.glUniform1i(uniFogToggle, config.showFog() ? 1 : 0);

		// Sets the color of the solid skybox (a clear color rendered before everything else).
		gl.glClearColor(rgbAvg[0], rgbAvg[1], rgbAvg[2], 1f);
		gl.glClear(gl.GL_COLOR_BUFFER_BIT);

		gl.glUniform4f(uniFogColor, rgbAvg[0], rgbAvg[1], rgbAvg[2], 1f);
	}

	private int getColorForTile(int lx, int ly, int xlen, int ylen, float[] rgbAvg)
	{
		WorldPoint twp = WorldPoint.fromLocalInstance(client, new LocalPoint(lx, ly));
		if (twp == null)
		{
			return 0;
		}

		int px = twp.getX() / SCALE + shift.getX();
		int py = skybox.getHeight() - (twp.getY() / SCALE + shift.getY());

		if (px < 0 || py < 0 || px >= skybox.getWidth() || py >= skybox.getHeight())
		{
			return 0;
		}

		int area = xlen * ylen;

		int rgb = skybox.getRGB(px, py);

		rgbAvg[0] += ((rgb >> 16) & 0xFF) * area;
		rgbAvg[1] += ((rgb >> 8) & 0xFF) * area;
		rgbAvg[2] += (rgb & 0xFF) * area;

		return area;
	}

	private void drawUi(final int canvasHeight, final int canvasWidth)
	{
		final BufferProvider bufferProvider = client.getBufferProvider();
		final int[] pixels = bufferProvider.getPixels();
		final int width = bufferProvider.getWidth();
		final int height = bufferProvider.getHeight();

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			gl.glEnable(gl.GL_BLEND);
		}
		else
		{
			gl.glDisable(gl.GL_BLEND);
		}

		gl.glViewport(0, 0, canvasWidth, canvasHeight);

		vertexBuffer.clear(); // reuse vertex buffer for interface
		vertexBuffer.ensureCapacity(pixels.length);

		IntBuffer interfaceBuffer = vertexBuffer.getBuffer();
		interfaceBuffer.put(pixels);
		vertexBuffer.flip();

		gl.glBlendFunc(gl.GL_SRC_ALPHA, gl.GL_ONE_MINUS_SRC_ALPHA);
		gl.glBindTexture(gl.GL_TEXTURE_2D, interfaceTexture);

		if (canvasWidth != lastCanvasWidth || canvasHeight != lastCanvasHeight)
		{
			gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGBA, width, height, 0, gl.GL_BGRA, gl.GL_UNSIGNED_INT_8_8_8_8_REV, interfaceBuffer);
			lastCanvasWidth = canvasWidth;
			lastCanvasHeight = canvasHeight;
		}
		else
		{
			gl.glTexSubImage2D(gl.GL_TEXTURE_2D, 0, 0, 0, width, height, gl.GL_BGRA, gl.GL_UNSIGNED_INT_8_8_8_8_REV, interfaceBuffer);
		}

		gl.glUseProgram(glUiProgram);

		// Bind texture to shader
		gl.glActiveTexture(gl.GL_TEXTURE0);
		gl.glBindTexture(gl.GL_TEXTURE_2D, interfaceTexture);
		gl.glUniform1i(uniTex, 0);

		// Texture on UI
		gl.glBindVertexArray(vaoUiHandle);
		gl.glDrawArrays(gl.GL_QUADS, 0, 4);

		// Reset
		gl.glBindTexture(gl.GL_TEXTURE_2D, 0);
		gl.glBindVertexArray(0);
		gl.glUseProgram(0);
		gl.glBlendFunc(gl.GL_SRC_ALPHA, gl.GL_ONE_MINUS_SRC_ALPHA);
		gl.glDisable(gl.GL_BLEND);

		vertexBuffer.clear();
	}

	/**
	 * Convert the front framebuffer to an Image
	 *
	 * @return
	 */
	private Image screenshot()
	{
		final int width = client.getCanvasWidth();
		final int height = client.getCanvasHeight();

		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4)
			.order(ByteOrder.nativeOrder());

		gl.glReadBuffer(gl.GL_FRONT);
		gl.glReadPixels(0, 0, width, height, GL.GL_RGBA, gl.GL_UNSIGNED_BYTE, buffer);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

		for (int y = 0; y < height; ++y)
		{
			for (int x = 0; x < width; ++x)
			{
				int r = buffer.get() & 0xff;
				int g = buffer.get() & 0xff;
				int b = buffer.get() & 0xff;
				buffer.get(); // alpha

				pixels[(height - y - 1) * width + x] = (r << 16) | (g << 8) | b;
			}
		}

		return image;
	}

	@Override
	public void animate(Texture texture, int diff)
	{
		textureManager.animate(texture, diff);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		uploadScene();
	}

	private void uploadScene()
	{
		vertexBuffer.clear();
		uvBuffer.clear();

		sceneUploader.upload(client.getScene(), vertexBuffer, uvBuffer);

		vertexBuffer.flip();
		uvBuffer.flip();

		IntBuffer vertexBuffer = this.vertexBuffer.getBuffer();
		FloatBuffer uvBuffer = this.uvBuffer.getBuffer();

		if (bufferId != -1)
		{
			GLUtil.glDeleteBuffer(gl, bufferId);
			bufferId = -1;
		}

		if (uvBufferId != -1)
		{
			GLUtil.glDeleteBuffer(gl, uvBufferId);
			uvBufferId = -1;
		}

		bufferId = glGenBuffers(gl);
		uvBufferId = glGenBuffers(gl);

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, bufferId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER, vertexBuffer.limit() * Integer.BYTES, vertexBuffer, gl.GL_STATIC_COPY);

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, uvBufferId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER, uvBuffer.limit() * Float.BYTES, uvBuffer, gl.GL_STATIC_COPY);

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, 0);

		vertexBuffer.clear();
		uvBuffer.clear();
	}

	/**
	 * Check is a model is visible and should be drawn.
	 */
	private boolean isVisible(Model model, int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int _x, int _y, int _z, long hash)
	{
		final int XYZMag = model.getXYZMag();
		final int zoom = client.get3dZoom();
		final int modelHeight = model.getModelHeight();

		int Rasterizer3D_clipMidX2 = client.getRasterizer3D_clipMidX2();
		int Rasterizer3D_clipNegativeMidX = client.getRasterizer3D_clipNegativeMidX();
		int Rasterizer3D_clipNegativeMidY = client.getRasterizer3D_clipNegativeMidY();
		int Rasterizer3D_clipMidY2 = client.getRasterizer3D_clipMidY2();

		int var11 = yawCos * _z - yawSin * _x >> 16;
		int var12 = pitchSin * _y + pitchCos * var11 >> 16;
		int var13 = pitchCos * XYZMag >> 16;
		int var14 = var12 + var13;
		if (var14 > 50)
		{
			int var15 = _z * yawSin + yawCos * _x >> 16;
			int var16 = (var15 - XYZMag) * zoom;
			if (var16 / var14 < Rasterizer3D_clipMidX2)
			{
				int var17 = (var15 + XYZMag) * zoom;
				if (var17 / var14 > Rasterizer3D_clipNegativeMidX)
				{
					int var18 = pitchCos * _y - var11 * pitchSin >> 16;
					int var19 = pitchSin * XYZMag >> 16;
					int var20 = (var18 + var19) * zoom;
					if (var20 / var14 > Rasterizer3D_clipNegativeMidY)
					{
						int var21 = (pitchCos * modelHeight >> 16) + var19;
						int var22 = (var18 - var21) * zoom;
						if (var22 / var14 < Rasterizer3D_clipMidY2)
						{
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	/**
	 * Draw a renderable in the scene
	 *
	 * @param renderable
	 * @param orientation
	 * @param pitchSin
	 * @param pitchCos
	 * @param yawSin
	 * @param yawCos
	 * @param x
	 * @param y
	 * @param z
	 * @param hash
	 */
	@Override
	public void draw(Renderable renderable, int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z, long hash)
	{
		// Model may be in the scene buffer
		if (renderable instanceof Model && ((Model) renderable).getSceneId() == sceneUploader.sceneId)
		{
			Model model = (Model) renderable;

			model.calculateBoundsCylinder();
			model.calculateExtreme(orientation);

			if (!isVisible(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash))
			{
				return;
			}

			client.checkClickbox(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash);

			int tc = Math.min(MAX_TRIANGLE, model.getTrianglesCount());
			int uvOffset = model.getUvBufferOffset();

			GpuIntBuffer b = bufferForTriangles(tc);

			b.ensureCapacity(8);
			IntBuffer buffer = b.getBuffer();
			buffer.put(model.getBufferOffset());
			buffer.put(uvOffset);
			buffer.put(tc);
			buffer.put(targetBufferOffset);
			buffer.put(FLAG_SCENE_BUFFER | (model.getRadius() << 12) | orientation);
			buffer.put(x).put(y).put(z);

			targetBufferOffset += tc * 3;
		}
		else
		{
			// Temporary model (animated or otherwise not a static Model on the scene)
			Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
			if (model != null)
			{
				// Apply height to renderable from the model
				model.setModelHeight(model.getModelHeight());

				model.calculateBoundsCylinder();
				model.calculateExtreme(orientation);

				if (!isVisible(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash))
				{
					return;
				}

				client.checkClickbox(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash);

				boolean hasUv = model.getFaceTextures() != null;

				int faces = Math.min(MAX_TRIANGLE, model.getTrianglesCount());
				vertexBuffer.ensureCapacity(12 * faces);
				uvBuffer.ensureCapacity(12 * faces);
				int len = 0;
				for (int i = 0; i < faces; ++i)
				{
					len += sceneUploader.pushFace(model, i, vertexBuffer, uvBuffer);
				}

				GpuIntBuffer b = bufferForTriangles(faces);

				b.ensureCapacity(8);
				IntBuffer buffer = b.getBuffer();
				buffer.put(tempOffset);
				buffer.put(hasUv ? tempUvOffset : -1);
				buffer.put(len / 3);
				buffer.put(targetBufferOffset);
				buffer.put((model.getRadius() << 12) | orientation);
				buffer.put(x).put(y).put(z);

				tempOffset += len;
				if (hasUv)
				{
					tempUvOffset += len;
				}

				targetBufferOffset += len;
			}
		}
	}

	/**
	 * returns the correct buffer based on triangle count and updates model count
	 *
	 * @param triangles
	 * @return
	 */
	private GpuIntBuffer bufferForTriangles(int triangles)
	{
		if (triangles < SMALL_TRIANGLE_COUNT)
		{
			++smallModels;
			return modelBufferSmall;
		}
		else
		{
			++largeModels;
			return modelBuffer;
		}
	}

}
