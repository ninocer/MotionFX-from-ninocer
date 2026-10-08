package dev.motionfx.app

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.GLES30
import android.view.Surface
import dev.motionfx.plugin.Frame
import dev.motionfx.plugin.GpuBackend
import dev.motionfx.plugin.ResolvedOperation
import dev.motionfx.plugin.ShaderCompiler
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded offscreen GLES3 renderer; all source comes from host templates. Use and close on one worker thread. */
class GlRenderer(private val width: Int, private val height: Int, output: Surface? = null) : GpuBackend, Closeable {
    private val display = EglLifetime.acquire()
    private var context = EGL14.EGL_NO_CONTEXT
    private var surface = EGL14.EGL_NO_SURFACE
    private val texture = IntArray(1)
    private var program = 0
    private var source = ""
    private val transfer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    private var closed = false

    init {
        try {
            val config = arrayOfNulls<EGLConfig>(1)
            val attrs = mutableListOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, 0x0040,
                EGL14.EGL_SURFACE_TYPE, if (output == null) EGL14.EGL_PBUFFER_BIT else EGL14.EGL_WINDOW_BIT,
            )
            if (output != null) attrs += listOf(0x3142, 1) // EGL_RECORDABLE_ANDROID
            attrs += EGL14.EGL_NONE
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attrs.toIntArray(), 0, config, 0, 1, count, 0) && count[0] > 0) { "No GLES3 EGL configuration" }
            context = EGL14.eglCreateContext(display, config[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "GLES3 context unavailable" }
            surface = if (output == null) EGL14.eglCreatePbufferSurface(display, config[0], intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
                else EGL14.eglCreateWindowSurface(display, config[0], output, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "EGL surface unavailable" }
            current()
            GLES30.glGenTextures(1, texture, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        } catch (e: Exception) { close(); throw e }
    }

    override fun apply(frame: Frame, operations: List<ResolvedOperation>): Frame {
        draw(frame, ShaderCompiler.fragment(operations), flip = false)
        transfer.clear()
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, transfer)
        checkGl()
        transfer.rewind()
        val pixels = IntArray(width * height) {
            val r = transfer.get().toInt() and 255; val g = transfer.get().toInt() and 255
            val b = transfer.get().toInt() and 255; val a = transfer.get().toInt() and 255
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Frame(width, height, pixels)
    }

    fun encode(frame: Frame, presentationTimeNs: Long) {
        draw(frame, PASSTHROUGH, flip = true)
        check(EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNs))
        check(EGL14.eglSwapBuffers(display, surface)) { "Encoder surface swap failed" }
    }

    private fun draw(frame: Frame, fragment: String, flip: Boolean) {
        require(frame.width == width && frame.height == height)
        current()
        if (source != fragment) {
            if (program != 0) GLES30.glDeleteProgram(program)
            program = 0
            val vs = compile(GLES30.GL_VERTEX_SHADER, ShaderCompiler.vertex)
            val fs = try { compile(GLES30.GL_FRAGMENT_SHADER, fragment) } catch (e: Exception) { GLES30.glDeleteShader(vs); throw e }
            try {
                program = GLES30.glCreateProgram()
                GLES30.glAttachShader(program, vs); GLES30.glAttachShader(program, fs); GLES30.glLinkProgram(program)
                val status = IntArray(1); GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
                check(status[0] == GLES30.GL_TRUE) { "GPU program link failed: ${GLES30.glGetProgramInfoLog(program)}" }
                source = fragment
            } finally { GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs) }
        }
        transfer.clear()
        val pixels = frame.pixels()
        for (y in 0 until height) for (x in 0 until width) {
            val p = pixels[(if (flip) height - 1 - y else y) * width + x]
            transfer.put((p ushr 16).toByte()); transfer.put((p ushr 8).toByte()); transfer.put(p.toByte()); transfer.put((p ushr 24).toByte())
        }
        transfer.flip()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, transfer)
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "sourceImage"), 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisable(GLES30.GL_DITHER)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        checkGl()
    }

    private fun current() { check(!closed && EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL context lost" } }
    private fun checkGl() { val error = GLES30.glGetError(); check(error == GLES30.GL_NO_ERROR) { "OpenGL error $error" } }
    private fun compile(type: Int, code: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, code); GLES30.glCompileShader(shader)
        val result = IntArray(1); GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, result, 0)
        if (result[0] != GLES30.GL_TRUE) {
            val error = GLES30.glGetShaderInfoLog(shader); GLES30.glDeleteShader(shader)
            error("GPU shader compilation failed: $error")
        }
        return shader
    }
    override fun close() {
        if (closed) return
        if (context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, surface, surface, context)
            if (program != 0) GLES30.glDeleteProgram(program)
            GLES30.glDeleteTextures(1, texture, 0)
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EglLifetime.release()
        closed = true
    }
    companion object {
        private val PASSTHROUGH = """#version 300 es
precision highp float;
uniform sampler2D sourceImage;
in vec2 uv;
out vec4 resultColor;
void main() { vec4 c = texture(sourceImage, uv); resultColor = vec4(c.rgb * c.a, 1.0); }
"""
    }
}

/** Release display resources after the last renderer, without invalidating an in-flight encoder context. */
private object EglLifetime {
    private var references = 0
    private var display = EGL14.EGL_NO_DISPLAY
    @Synchronized fun acquire(): EGLDisplay {
        if (references == 0) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)) { "EGL initialization failed" }
        }
        references++
        return display
    }
    @Synchronized fun release() {
        references--
        if (references == 0) { EGL14.eglTerminate(display); display = EGL14.EGL_NO_DISPLAY }
    }
}
