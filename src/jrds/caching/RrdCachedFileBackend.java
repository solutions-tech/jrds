package jrds.caching;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.log4j.Logger;
import org.rrd4j.core.RrdBackend;

/** 
 * JRobin backend which is used to store RRD data to ordinary disk files 
 * by using fast java.nio.* package ehanced with caching functionnalities.
 * @author Fabrice Bacchella
 */
public class RrdCachedFileBackend extends RrdBackend {
    static final private Logger logger = Logger.getLogger(RrdCachedFileBackend.class);

    private native static void prepare_fd(String filename, FileDescriptor fdobj, boolean readOnly);

    private static final FileChannel DirectFile(File direct, boolean readOnly) throws IOException {
      FileDescriptor fd = new FileDescriptor();
      prepare_fd(direct.getCanonicalPath(), fd, readOnly);
      return new FileInputStream(fd).getChannel();
    }
    
    /**
     * Read/write file status.
     */
    private final boolean readOnly;

    private static final int CACHE_LENGTH = 8192;
    private ByteBuffer byteBuffer;
    private int cacheSize;
    private long cacheStart;
    private boolean cacheDirty = false;
    private int dirtyStart;
    private int dirtyEnd;

    private static final Timer syncTimer = new Timer(true);
    private int syncMode;
    private TimerTask syncTask;

    private static int writeHit = 0;
    private static int readHit = 0;
    private static int access = 0;

    /**
     * Random access file handle.
     */
    protected final FileChannel channel;

    /**
     * Creates RrdFileBackend object for the given file path, backed by RandomAccessFile object.
     *
     * @param path     Path to a file
     * @param readOnly True, if file should be open in a read-only mode. False otherwise
     * @throws IOException Thrown in case of I/O error
     */
    protected RrdCachedFileBackend(String path, boolean readOnly) throws IOException {
        super(path);
        this.readOnly = readOnly;
        this.channel = DirectFile(new File(path), readOnly);
    }

    /**
     * @param path
     */
    public RrdCachedFileBackend(String path, boolean readOnly, int lockMode, int syncMode, int syncPeriod)
            throws IOException {
        this(path, readOnly);
        this.syncMode = syncMode;
    }

    /**
     * This method forces all data cached in memory but not yet stored in the file,
     * to be stored in it. RrdCachedFileBackend uses  memory to cache I/O data.
     * This method is automatically invoked when the {@link #close()}
     * method is called. In other words, you don't have to call sync() before you call close().<p>
     */
    public synchronized void sync() {
        if(cacheDirty) {
            try {
                int oldLimit = byteBuffer.limit();
                byteBuffer.position(dirtyStart);
                byteBuffer.limit(dirtyEnd);
                channel.write(byteBuffer, cacheStart + dirtyStart);
                byteBuffer.limit(oldLimit);
                cacheDirty = false;
                dirtyStart = byteBuffer.capacity();
                dirtyEnd = 0;
            } catch (IOException e) {
                logger.error("Panic while syncing " +  this.getPath() + ": " + e);
            }
        }
    }

    /**
     * Method called by the framework immediatelly before RRD update operation starts. This method
     * will synchronize in-memory cache with the disk content if synchronization mode is set to
     * {@link RrdCachedFileBackendFactory#SYNC_BEFOREUPDATE}. Otherwise it does nothing.
     */
    public void beforeUpdate() {
        if(syncMode == RrdCachedFileBackendFactory.SYNC_BEFOREUPDATE) {
            sync();
        }
    }

    /**
     * Method called by the framework immediatelly after RRD update operation finishes. This method
     * will synchronize in-memory cache with the disk content if synchronization mode is set to
     * {@link RrdCachedFileBackendFactory#SYNC_AFTERUPDATE}. Otherwise it does nothing.
     */
    public void afterUpdate() {
        if(syncMode == RrdCachedFileBackendFactory.SYNC_AFTERUPDATE) {
            sync();
        }
    }

    /**
     * Method called by the framework immediatelly before RRD fetch operation starts. This method
     * will synchronize in-memory cache with the disk content if synchronization mode is set to
     * {@link RrdCachedFileBackendFactory#SYNC_BEFOREFETCH}. Otherwise it does nothing.
     */
    public void beforeFetch() {
        if(syncMode == RrdCachedFileBackendFactory.SYNC_BEFOREFETCH) {
            sync();
        }
    }

    /**
     * Method called by the framework immediatelly after RRD fetch operation finishes. This method
     * will synchronize in-memory cache with the disk content if synchronization mode is set to
     * {@link RrdCachedFileBackendFactory#SYNC_AFTERFETCH}. Otherwise it does nothing.
     */
    public void afterFetch() {
        if(syncMode == RrdCachedFileBackendFactory.SYNC_AFTERFETCH) {
            sync();
        }
    }

    /**
     * Writes bytes to the underlying RRD file on the disk
     * @param offset Starting file offset
     * @param b Bytes to be written.
     * @throws IOException Thrown in case of I/O error
     */
    public void write(long offset, byte[] b) throws IOException {
        if( Thread.currentThread().isInterrupted()) {
            close();
            throw new ClosedByInterruptException();
        }
        else {
            if(b.length > 0) {
                access++;
                long cacheEnd = cacheStart + cacheSize - 1;
                if(byteBuffer == null || offset < cacheStart || offset + b.length > cacheEnd) {
                    try {
                        cacheMiss(offset, b.length);
                    }
                    catch (IOException e) {
                        if( e instanceof ClosedByInterruptException) {
                            //too late, it's already close
                            cacheDirty = false;
                            logger.error("data dropped by an IO exception");
                            close();
                        }
                        throw e;
                    }
                }
                else
                    writeHit++;
                synchronized(this) {
                    try {
                        cacheDirty = true;
                        byteBuffer.position((int)(offset - cacheStart));
                        byteBuffer.put(b);
                        dirtyStart = Math.min((int)(offset - cacheStart), dirtyStart);
                        dirtyEnd = Math.max(dirtyEnd, byteBuffer.position());
                    } catch (RuntimeException e) {
                        logger.fatal("data dropped at offset "  + offset + ", cache starting at " + cacheStart, e);
                    }
                }
            }
        }
    }

    /**
     * Reads a number of bytes from the RRD file on the disk
     * @param offset Starting file offset
     * @param b Buffer which receives bytes read from the file.
     * @throws IOException Thrown in case of I/O error.
     */
    public void read(long offset, byte[] b) throws IOException {
        if( Thread.currentThread().isInterrupted()) {
            close();
            throw new ClosedByInterruptException();
        }
        else {
            if(b.length > 0) {
                access++;
                long cacheEnd = cacheStart + cacheSize - 1;
                if(byteBuffer == null || offset < cacheStart || offset + b.length > cacheEnd) {
                    try {
                        cacheMiss(offset, b.length);
                    }
                    catch (IOException e) {
                        if( e instanceof ClosedByInterruptException) {
                            //too late, it's already close
                            cacheDirty = false;
                            logger.error("data dropped by an exception");
                            close();
                        }
                        throw e;
                    }
                }
                else {
                    readHit++;
                }
                byteBuffer.position((int)(offset - cacheStart));
                byteBuffer.get(b);
            }
        }
    }

    /**
     * Called after a cache miss, update the bytebuffer using 8k pages 
     * arround the desired offset and size   * @param offset
     * @param length
     * @throws IOException
     */
    private void cacheMiss(long offset, long length) throws IOException {
        logger.trace("cache miss of " + length +" bytes at position " + offset);
        if(cacheDirty)
            sync();
        int newCacheSize = (int) (Math.ceil((double)(length + offset % CACHE_LENGTH)/  CACHE_LENGTH) * CACHE_LENGTH);
        //long newCacheStart = (offset % CACHE_LENGTH) * CACHE_LENGTH;
        long newCacheStart = (long) (Math.floor((double)offset /  CACHE_LENGTH) * CACHE_LENGTH);
        ByteBuffer newByteBuffer = ByteBuffer.allocate(newCacheSize);
        synchronized(channel) {
            channel.position(newCacheStart);
            int bread = channel.read(newByteBuffer);
            if( bread < length) {
                throw new IOException("Not enough bytes available in file " + getPath());
            }
            else {
                synchronized(this) {
                    byteBuffer= newByteBuffer;
                    cacheDirty = false;
                    dirtyStart = byteBuffer.capacity();
                    dirtyEnd = 0;
                    cacheSize = newCacheSize;
                    cacheStart = newCacheStart;
                }
            }
        }

    }

    /** 
     * Closes the underlying RRD file. 
     * @throws IOException Thrown in case of I/O error 
     */
    public void close() throws IOException {
        // cancel synchronization
        if(syncTask != null) {
            syncTask.cancel();
        }


        super.close(); // calls sync() eventually
        // release the buffer, make it eligible for GC as soon as possible
        byteBuffer = null;
    }

    /** 
     * Calculate the cache efficiency, accross all the backend used 
     * @return cache efficiency 
     */
    public static float getCacheEfficency() {
        return ((float)readHit + writeHit)/access;
    }

    /** 
     * @return the number of IO operations that hit the cache, 
     * accross all the backend used 
     */
    public static int getCacheHitsCount() {
        return readHit + writeHit;
    }

    /** 
     * @return the number of IO operations, 
     * accross all the backend used 
     */ 
    public static int getCacheRequestsCount() {
        return access;

    }

    /**
     * Returns RRD file length.
     *
     * @return File length.
     * @throws IOException Thrown in case of I/O error.
     */
    public long getLength() throws IOException {
        return channel.size();
    }

    /**
     * Sets length of the underlying RRD file. This method is called only once, immediately
     * after a new RRD file gets created.
     *
     * @param length Length of the RRD file
     * @throws IOException Thrown in case of I/O error.
     */
    protected void setLength(long length) throws IOException {
        if(channel.size() < length)
            channel.truncate(length);
        else {
            channel.position(length);
            channel.write(ByteBuffer.allocate(1));
        }
    }

}