package de.swfdtf.graphics;

import java.util.Objects;

/**
 * Dynamic static-UI subtree caching container ({@code RenderCacheNode}).
 *
 * <p>Tracks structural and visual dirtiness of its static child tree so repeated frames can issue a single
 * cached texture draw call instead of re-traversing complex card hierarchies until {@link #invalidateCache()}
 * or child mutation occurs.</p>
 */
public class RenderCacheNode extends SceneNode {

    private boolean cacheDirty = true;
    private long cacheVersion = 0L;
    private int cachedBakeCount = 0;
    private Framebuffer cacheFbo;

    /**
     * Creates a {@code RenderCacheNode}.
     *
     * @param id optional identifier
     */
    public RenderCacheNode(String id) {
        super(id, "render_cache");
    }

    /**
     * Marks the cached layer representation dirty so the next frame refreshes it.
     */
    public void invalidateCache() {
        this.cacheDirty = true;
    }

    /**
     * Returns whether the cache needs re-baking before reuse.
     *
     * @return {@code true} if dirty
     */
    public boolean isCacheDirty() {
        return cacheDirty;
    }

    /**
     * Records a clean bake transition for headless verification or OpenGL FBO reuse.
     *
     * @return updated cache version number
     */
    public long markCacheBaked() {
        this.cacheDirty = false;
        this.cachedBakeCount++;
        this.cacheVersion++;
        return cacheVersion;
    }

    /**
     * Returns total number of full subtree bakes performed so far.
     *
     * @return bake count
     */
    public int getCachedBakeCount() {
        return cachedBakeCount;
    }

    /**
     * Returns monotonic baked cache version counter.
     *
     * @return cache version
     */
    public long getCacheVersion() {
        return cacheVersion;
    }

    /**
     * Computes exact draw calls consumed for rendering this static cache node (1 when cleanly baked,
     * child count + 1 when dirty and rebaking).
     *
     * @return draw call count for telemetry
     */
    public int getEffectiveDrawCallCount() {
        return cacheDirty ? (1 + countDescendants(this)) : 1;
    }

    @Override
    public SceneNode addChild(SceneNode child) {
        Objects.requireNonNull(child, "child must not be null");
        invalidateCache();
        return super.addChild(child);
    }

    @Override
    public boolean removeChild(SceneNode child) {
        boolean removed = super.removeChild(child);
        if (removed) {
            invalidateCache();
        }
        return removed;
    }

    @Override
    public void clearChildren() {
        invalidateCache();
        super.clearChildren();
    }

    /**
     * Releases any backing offscreen FBO held by this node.
     */
    public void disposeCacheFbo() {
        if (cacheFbo != null) {
            cacheFbo.dispose();
            cacheFbo = null;
        }
        invalidateCache();
    }

    private static int countDescendants(SceneNode root) {
        int count = 0;
        for (SceneNode child : root.getChildren()) {
            count += 1 + countDescendants(child);
        }
        return count;
    }
}
