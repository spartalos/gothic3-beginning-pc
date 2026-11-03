package javax.microedition.rms;

import gothic3.platform.Platform;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * File-backed RecordStore. One file per store ("&lt;urlencoded name&gt;.rms") in
 * Platform.rmsDir(); rewritten atomically on every mutation. Record ids start at 1 and
 * are never reused (nextId is persisted).
 */
public class RecordStore {
    private static final int MAGIC = 0x47335253; // "G3RS"
    private static final int CAPACITY = 8 * 1024 * 1024;

    /** Shared state of one named store (multiple open handles share it). */
    private static final class Data {
        final String name;
        int nextId = 1, version = 0, openCount = 0;
        long lastModified = System.currentTimeMillis();
        final TreeMap<Integer, byte[]> records = new TreeMap<>();
        final List<RecordListener> listeners = new ArrayList<>();
        Data(String name) { this.name = name; }
        int usedBytes() { int s = 0; for (byte[] b : records.values()) s += b.length; return s; }
    }

    private static final Map<String, Data> OPEN = new HashMap<>();

    private final Data d;
    private boolean closed;

    private RecordStore(Data d) { this.d = d; }

    private static File fileFor(String name) {
        return new File(Platform.rmsDir(), URLEncoder.encode(name, StandardCharsets.UTF_8).replace("*", "%2A") + ".rms");
    }

    public static synchronized RecordStore openRecordStore(String name, boolean createIfNecessary)
            throws RecordStoreException {
        if (name == null || name.isEmpty() || name.length() > 32) throw new IllegalArgumentException("bad name");
        Data d = OPEN.get(name);
        if (d == null) {
            File f = fileFor(name);
            d = new Data(name);
            if (f.exists()) {
                try {
                    load(f, d);
                } catch (IOException e) {
                    throw new RecordStoreException("cannot read " + name + ": " + e);
                }
            } else {
                if (!createIfNecessary) throw new RecordStoreNotFoundException(name);
                try {
                    save(d);
                } catch (IOException e) {
                    throw new RecordStoreException("cannot create " + name + ": " + e);
                }
            }
            OPEN.put(name, d);
        }
        d.openCount++;
        return new RecordStore(d);
    }

    public static synchronized void deleteRecordStore(String name) throws RecordStoreException {
        if (OPEN.containsKey(name)) throw new RecordStoreException("store is open: " + name);
        File f = fileFor(name);
        if (!f.exists()) throw new RecordStoreNotFoundException(name);
        if (!f.delete()) throw new RecordStoreException("cannot delete " + name);
    }

    public static synchronized String[] listRecordStores() {
        File[] fs = Platform.rmsDir().listFiles((dir, n) -> n.endsWith(".rms"));
        if (fs == null || fs.length == 0) return null;
        List<String> out = new ArrayList<>();
        for (File f : fs) {
            String n = f.getName();
            out.add(java.net.URLDecoder.decode(n.substring(0, n.length() - 4), StandardCharsets.UTF_8));
        }
        return out.toArray(new String[0]);
    }

    private static void load(File f, Data d) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != MAGIC) throw new IOException("bad magic");
            d.version = in.readInt();
            d.nextId = in.readInt();
            d.lastModified = in.readLong();
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int id = in.readInt();
                byte[] b = new byte[in.readInt()];
                in.readFully(b);
                d.records.put(id, b);
            }
        }
    }

    private static void save(Data d) throws IOException {
        File f = fileFor(d.name);
        File dir = f.getParentFile();
        dir.mkdirs();
        File tmp = new File(dir, f.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(d.version);
            out.writeInt(d.nextId);
            out.writeLong(d.lastModified);
            out.writeInt(d.records.size());
            for (Map.Entry<Integer, byte[]> e : d.records.entrySet()) {
                out.writeInt(e.getKey());
                out.writeInt(e.getValue().length);
                out.write(e.getValue());
            }
        }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void checkOpen() throws RecordStoreNotOpenException {
        if (closed) throw new RecordStoreNotOpenException();
    }

    private void touch() throws RecordStoreException {
        d.version++;
        d.lastModified = System.currentTimeMillis();
        try {
            save(d);
        } catch (IOException e) {
            throw new RecordStoreException("write failed: " + e);
        }
    }

    public synchronized void closeRecordStore() throws RecordStoreNotOpenException {
        checkOpen();
        closed = true;
        synchronized (RecordStore.class) {
            if (--d.openCount <= 0) OPEN.remove(d.name);
        }
    }

    public String getName() throws RecordStoreNotOpenException { checkOpen(); return d.name; }
    public int getVersion() throws RecordStoreNotOpenException { checkOpen(); return d.version; }
    public synchronized int getNumRecords() throws RecordStoreNotOpenException { checkOpen(); return d.records.size(); }
    public synchronized int getSize() throws RecordStoreNotOpenException { checkOpen(); return d.usedBytes() + 16; }
    public synchronized int getSizeAvailable() throws RecordStoreNotOpenException {
        checkOpen();
        return Math.max(0, CAPACITY - d.usedBytes());
    }
    public long getLastModified() throws RecordStoreNotOpenException { checkOpen(); return d.lastModified; }
    public synchronized int getNextRecordID() throws RecordStoreNotOpenException { checkOpen(); return d.nextId; }

    public synchronized int addRecord(byte[] data, int offset, int numBytes) throws RecordStoreException {
        checkOpen();
        if (numBytes < 0 || (data == null && numBytes > 0) || (data != null && offset < 0 || data != null && offset + numBytes > data.length))
            throw new ArrayIndexOutOfBoundsException();
        if (d.usedBytes() + numBytes > CAPACITY) throw new RecordStoreFullException();
        int id = d.nextId++;
        d.records.put(id, data == null ? new byte[0] : Arrays.copyOfRange(data, offset, offset + numBytes));
        touch();
        for (RecordListener l : d.listeners) l.recordAdded(this, id);
        return id;
    }

    public synchronized void deleteRecord(int recordId) throws RecordStoreException {
        checkOpen();
        if (d.records.remove(recordId) == null) throw new InvalidRecordIDException();
        touch();
        for (RecordListener l : d.listeners) l.recordDeleted(this, recordId);
    }

    public synchronized int getRecordSize(int recordId) throws RecordStoreException {
        checkOpen();
        byte[] b = d.records.get(recordId);
        if (b == null) throw new InvalidRecordIDException();
        return b.length;
    }

    public synchronized byte[] getRecord(int recordId) throws RecordStoreException {
        checkOpen();
        byte[] b = d.records.get(recordId);
        if (b == null) throw new InvalidRecordIDException();
        return b.clone();
    }

    public synchronized int getRecord(int recordId, byte[] buffer, int offset) throws RecordStoreException {
        checkOpen();
        byte[] b = d.records.get(recordId);
        if (b == null) throw new InvalidRecordIDException();
        if (offset < 0 || offset + b.length > buffer.length) throw new ArrayIndexOutOfBoundsException();
        System.arraycopy(b, 0, buffer, offset, b.length);
        return b.length;
    }

    public synchronized void setRecord(int recordId, byte[] newData, int offset, int numBytes) throws RecordStoreException {
        checkOpen();
        byte[] old = d.records.get(recordId);
        if (old == null) throw new InvalidRecordIDException();
        if (numBytes < 0 || (newData == null && numBytes > 0) || (newData != null && (offset < 0 || offset + numBytes > newData.length)))
            throw new ArrayIndexOutOfBoundsException();
        if (d.usedBytes() - old.length + numBytes > CAPACITY) throw new RecordStoreFullException();
        d.records.put(recordId, newData == null ? new byte[0] : Arrays.copyOfRange(newData, offset, offset + numBytes));
        touch();
        for (RecordListener l : d.listeners) l.recordChanged(this, recordId);
    }

    public void addRecordListener(RecordListener l) { if (!d.listeners.contains(l)) d.listeners.add(l); }
    public void removeRecordListener(RecordListener l) { d.listeners.remove(l); }

    public synchronized RecordEnumeration enumerateRecords(RecordFilter filter, RecordComparator comparator,
            boolean keepUpdated) throws RecordStoreNotOpenException {
        checkOpen();
        return new Enum(filter, comparator);
    }

    private final class Enum implements RecordEnumeration {
        final RecordFilter filter;
        final RecordComparator cmp;
        List<Integer> ids = new ArrayList<>();
        int pos;
        Enum(RecordFilter f, RecordComparator c) { filter = f; cmp = c; rebuild(); }
        public void rebuild() {
            synchronized (RecordStore.this) {
                ids = new ArrayList<>();
                for (Map.Entry<Integer, byte[]> e : d.records.entrySet())
                    if (filter == null || filter.matches(e.getValue().clone())) ids.add(e.getKey());
                if (cmp != null)
                    ids.sort((a, b) -> cmp.compare(d.records.get(a), d.records.get(b)));
                pos = 0;
            }
        }
        public int numRecords() { return ids.size(); }
        public byte[] nextRecord() throws RecordStoreException { return getRecord(nextRecordId()); }
        public int nextRecordId() throws InvalidRecordIDException {
            if (pos >= ids.size()) throw new InvalidRecordIDException();
            return ids.get(pos++);
        }
        public byte[] previousRecord() throws RecordStoreException { return getRecord(previousRecordId()); }
        public int previousRecordId() throws InvalidRecordIDException {
            if (pos <= 0) throw new InvalidRecordIDException();
            return ids.get(--pos);
        }
        public boolean hasNextElement() { return pos < ids.size(); }
        public boolean hasPreviousElement() { return pos > 0; }
        public void reset() { pos = 0; }
        public void keepUpdated(boolean k) {}
        public boolean isKeptUpdated() { return false; }
        public void destroy() { ids = new ArrayList<>(); }
    }
}
