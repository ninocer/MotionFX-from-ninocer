package dev.motionfx.render

import android.graphics.Bitmap
import android.opengl.*
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Owns EGL on the export worker only. Uploads the reference compositor frame to codec's input surface. */
class EncoderSurface(surface: Surface, private val width: Int, private val height: Int): AutoCloseable {
    private val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context=EGL14.EGL_NO_CONTEXT
    private var window=EGL14.EGL_NO_SURFACE
    private var program=0
    private val texture=IntArray(1)
    private val vertices=ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f)); position(0)
    }
    init {
        try {
            check(EGL14.eglInitialize(display,null,0,null,0))
            val configs=arrayOfNulls<EGLConfig>(1); val count=IntArray(1)
            check(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
                EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,4,
                0x3142,1,EGL14.EGL_NONE),0,configs,0,1,count,0) && count[0]>0)
            context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE),0)
            check(context!=EGL14.EGL_NO_CONTEXT)
            window=EGL14.eglCreateWindowSurface(display,configs[0],surface,intArrayOf(EGL14.EGL_NONE),0)
            check(window!=EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display,window,window,context))
            val vertex=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 a; attribute vec2 uv; varying vec2 v; void main(){gl_Position=vec4(a,0.,1.);v=uv;}")
            val fragment=shader(GLES20.GL_FRAGMENT_SHADER,"precision mediump float; uniform sampler2D tex; varying vec2 v; void main(){gl_FragColor=texture2D(tex,v);}")
            program=GLES20.glCreateProgram(); GLES20.glAttachShader(program,vertex); GLES20.glAttachShader(program,fragment); GLES20.glLinkProgram(program)
            val ok=IntArray(1); GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0)
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
            check(ok[0]!=0) { GLES20.glGetProgramInfoLog(program) }
            GLES20.glGenTextures(1,texture,0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width,height,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null)
        } catch(t: Throwable) { close(); throw t }
    }
    private fun shader(type: Int, source: String): Int {
        val s=GLES20.glCreateShader(type); GLES20.glShaderSource(s,source); GLES20.glCompileShader(s)
        val ok=IntArray(1); GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0)
        if(ok[0]==0) { val log=GLES20.glGetShaderInfoLog(s); GLES20.glDeleteShader(s); error(log) }; return s
    }
    fun draw(bitmap: Bitmap, nanos: Long) {
        GLES20.glViewport(0,0,width,height); GLES20.glUseProgram(program)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture[0]); GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,bitmap)
        val a=GLES20.glGetAttribLocation(program,"a"); val uv=GLES20.glGetAttribLocation(program,"uv")
        vertices.position(0); GLES20.glVertexAttribPointer(a,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(a)
        vertices.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        check(GLES20.glGetError()==GLES20.GL_NO_ERROR) { "OpenGL export failed" }
        EGLExt.eglPresentationTimeANDROID(display,window,nanos)
        check(EGL14.eglSwapBuffers(display,window)) { "Encoder surface error" }
    }
    override fun close() {
        if(context!=EGL14.EGL_NO_CONTEXT) {
            if(program!=0) GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(1,texture,0)
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            if(window!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window)
            EGL14.eglDestroyContext(display,context)
        }
        EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
    }
}
