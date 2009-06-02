package jd.controlling;

import java.nio.ByteBuffer;

public class ByteBufferEntry {
    private ByteBuffer buffer = null;
    private long used = 0;
    private long freed = 0;
    private long lastaccess = 0;
    private long lastfree = 0;
    boolean inuse = false;

    public ByteBufferEntry(int size) {
        MemoryController.getInstance().increaseCreated(size);
        buffer = ByteBuffer.allocateDirect(size);
    }

    public int size() {
        return buffer.capacity();
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    public ByteBufferEntry getByteBufferEntry() {
        inuse = true;
        used++;
        lastaccess = System.currentTimeMillis();
        buffer.clear();
        return this;
    }

    public long lastAccess() {
        return lastaccess;
    }

    public long lastFree() {
        return lastfree;
    }

    public boolean inUse() {
        return inuse;
    }

    public void setUnused() {
        freed++;
        lastfree = System.currentTimeMillis();
        inuse = false;
    }

}