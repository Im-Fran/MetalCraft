package cl.franciscosolis.metalcraft.mtl;

/**
 * JNI bindings to libmetalcraft. Metal objects cross the boundary as retained pointers (long) that must be
 * handed back to {@link #release}. Must be called from the render thread.
 */
public final class Mtl {
    private Mtl() {}

    // MTLPrimitiveType
    public static final int PRIMITIVE_POINT = 0, PRIMITIVE_LINE = 1, PRIMITIVE_LINE_STRIP = 2, PRIMITIVE_TRIANGLE = 3, PRIMITIVE_TRIANGLE_STRIP = 4;
    // MTLCullMode
    public static final int CULL_NONE = 0, CULL_BACK = 2;
    // MTLCompareFunction
    public static final int COMPARE_LESS = 1, COMPARE_EQUAL = 2, COMPARE_LESS_EQUAL = 3, COMPARE_GREATER = 4, COMPARE_ALWAYS = 7;

    public static native String deviceName();
    public static native void init(long nsWindow);
    public static native void setVsync(boolean enabled);
    public static native int maxTextureSize();
    public static native void release(long handle);

    public static native long newBuffer(long size);
    public static native long bufferContents(long buffer);
    public static native void copyBuffer(long src, long srcOffset, long dst, long dstOffset, long length);
    public static native long newTextureBuffer(long buffer, int format, long length, int pixelSize);

    public static native long newTexture(int format, int width, int height, int mips, boolean cube, boolean renderTarget, String label);
    public static native long newTextureView(long texture, int baseMip, int mipCount);
    public static native void copyBufferToTexture(long buffer, long offset, int bytesPerRow, long texture, int slice, int mip, int x, int y, int w, int h);
    public static native void copyTextureToBuffer(long texture, int mip, int x, int y, int w, int h, long buffer, long offset, int bytesPerRow);
    public static native void copyTextureToTexture(long src, long dst, int mip, int dstX, int dstY, int srcX, int srcY, int w, int h);
    public static native long newSampler(boolean repeatU, boolean repeatV, boolean linearMin, boolean linearMag, int maxAnisotropy, float maxLod);

    public static native long newLibrary(String source);
    public static native long newFunction(long library, String name);
    public static native long newRenderPipeline(long vs, long fs, int colorFormat, int depthFormat,
                                                boolean blend, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha, int writeMask,
                                                int[] attribs, int[] missing, int stride, int vertexBufferIndex, String label);
    public static native long newDepthStencilState(int compare, boolean write);

    public static native void beginPass(long color, boolean clearColor, float r, float g, float b, float a, long depth, boolean clearDepth, double depthValue);
    public static native void endPass();
    public static native void clearRegion(long color, int colorFormat, long depth, float r, float g, float b, float a, float depthValue, int x, int y, int w, int h);
    public static native void setPipelineState(long pso, long depthState, int cull, boolean wireframe, float depthBiasConstant, float depthBiasSlope);
    public static native void setScissor(int x, int y, int w, int h);
    public static native void setBuffer(boolean fragment, int index, long buffer, long offset);
    public static native void setTexture(boolean fragment, int index, long texture, int samplerIndex, long sampler);
    public static native void draw(int primitive, int first, int count, int instances);
    public static native void drawIndexed(int primitive, int count, boolean uint32, long indexBuffer, long indexOffset, int instances, int baseVertex);

    public static native void setBytes(boolean fragment, int index, long address, int length);
    public static native void multiDrawIndexed(int primitive, boolean uint32, long indexBuffer, long counts, long offsets, long baseVertices, int drawCount);
    /** Address of a native OpenGL stand-in (see metalcraft.mm), or 0 for functions that aren't provided. */
    public static native long glStubAddress(String name);

    public static native void present(long texture);
    public static native long fence();
    public static native long completedFence();
    public static native double takeGpuSeconds();
    public static native long gpuNanosBetween(long firstFence, long lastFence);

    /** Dev-only per-pass GPU timing (-Dmetalcraft.gpuProfile); see metalcraft.mm. */
    public static final boolean GPU_PROFILING = Boolean.getBoolean("metalcraft.gpuProfile");
    public static native void setGpuProfiling(boolean enabled);
    public static native void setPassLabel(String label);
    public static native String takeGpuProfile();
    public static native boolean fenceWait(long value, long timeoutMs);
}
