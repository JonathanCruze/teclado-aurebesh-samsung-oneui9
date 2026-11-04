package com.aurebesh.teclado.llm;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lector de archivos GGUF (version 2 y 3, little-endian). Lee la cabecera, los metadatos y la
 * tabla de tensores en objetos Java normales y deja los datos de los tensores mapeados en memoria
 * (fuera del heap) con MappedByteBuffer: los pesos nunca se copian enteros al heap.
 *
 * Si el archivo pesa menos de 2 GB se hace un unico mapeo; si no, uno por tensor.
 */
final class Gguf implements Closeable {

    /** "GGUF" leido como u32 little-endian. */
    static final int MAGIC = 0x46554747;
    static final int DEFAULT_ALIGNMENT = 32;

    // Tipos de valor de los metadatos
    static final int T_UINT8 = 0, T_INT8 = 1, T_UINT16 = 2, T_INT16 = 3, T_UINT32 = 4, T_INT32 = 5,
            T_FLOAT32 = 6, T_BOOL = 7, T_STRING = 8, T_ARRAY = 9, T_UINT64 = 10, T_INT64 = 11, T_FLOAT64 = 12;

    /** Descripcion de un tensor del archivo (o de una vista de filas de otro tensor). */
    static final class Tensor {
        final String name;
        /** Dimensiones en orden GGUF: dims[0] es la mas rapida (columnas = entradas). */
        final long[] dims;
        final int type;
        /** Desplazamiento desde el inicio de la zona de datos. */
        final long offset;
        final int cols;          // dims[0]
        final int rows;          // producto del resto
        final int rowBytes;
        final long nbytes;
        final long nelements;
        /** Segmento mapeado y desplazamiento dentro de el (asignados al mapear). */
        int segment;
        int segmentOffset;

        Tensor(String name, long[] dims, int type, long offset) throws IOException {
            this.name = name;
            this.dims = dims;
            this.type = type;
            this.offset = offset;
            long c = dims.length > 0 ? dims[0] : 1;
            long r = 1;
            for (int i = 1; i < dims.length; i++) r *= dims[i];
            if (c <= 0 || c > Integer.MAX_VALUE || r <= 0 || r > Integer.MAX_VALUE) {
                throw new IOException("Dimensiones no admitidas en " + name);
            }
            int bs = Quant.blockSize(type);
            if (c % bs != 0) throw new IOException("El tensor " + name + " no es multiplo del bloque de " + Quant.name(type));
            this.cols = (int) c;
            this.rows = (int) r;
            long rb = c / bs * Quant.typeSize(type);
            if (rb > Integer.MAX_VALUE) throw new IOException("Fila demasiado grande en " + name);
            this.rowBytes = (int) rb;
            this.nelements = c * r;
            this.nbytes = rb * r;
        }

        /** Vista de un rango de filas de este tensor (para attn_qkv fusionado). */
        private Tensor(Tensor base, int firstRow, int nRows) throws IOException {
            this(base.name + "[" + firstRow + ":" + (firstRow + nRows) + "]",
                    new long[]{base.cols, nRows}, base.type, base.offset + (long) firstRow * base.rowBytes);
            this.segment = base.segment;
            this.segmentOffset = base.segmentOffset + firstRow * base.rowBytes;
        }

        Tensor view(int firstRow, int nRows) throws IOException {
            if (firstRow < 0 || firstRow + nRows > rows) throw new IOException("Vista fuera de rango en " + name);
            return new Tensor(this, firstRow, nRows);
        }

        /** Copia la fila r (rowBytes bytes) en dst usando el duplicado de buffer del hilo llamante. */
        void row(ByteBuffer dup, int r, byte[] dst) {
            ((Buffer) dup).position(segmentOffset + r * rowBytes);
            dup.get(dst, 0, rowBytes);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(name).append(' ').append(Quant.name(type)).append(" [");
            for (int i = 0; i < dims.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(dims[i]);
            }
            return sb.append(']').toString();
        }
    }

    final File file;
    final long fileSize;
    final int version;
    final int alignment;
    final long dataOffset;
    /** Metadatos: Integer/Long/Float/Double/Boolean/String o int[]/long[]/float[]/boolean[]/String[]/Object[]. */
    final Map<String, Object> meta = new LinkedHashMap<>();
    final Map<String, Tensor> tensors = new LinkedHashMap<>();
    final List<Tensor> tensorList = new ArrayList<>();

    private RandomAccessFile raf;
    private FileChannel channel;
    private MappedByteBuffer[] segments;

    /** Lector little-endian con buffer sobre el flujo del archivo, con posicion. */
    private static final class Reader {
        private final InputStream in;
        long pos;
        private final byte[] tmp = new byte[8];

        Reader(InputStream in) {
            this.in = in;
        }

        void fully(byte[] b, int n) throws IOException {
            int off = 0;
            while (off < n) {
                int r = in.read(b, off, n - off);
                if (r < 0) throw new EOFException("Archivo GGUF truncado");
                off += r;
            }
            pos += n;
        }

        int u8() throws IOException {
            fully(tmp, 1);
            return tmp[0] & 0xFF;
        }

        int u16() throws IOException {
            fully(tmp, 2);
            return (tmp[0] & 0xFF) | ((tmp[1] & 0xFF) << 8);
        }

        int i32() throws IOException {
            fully(tmp, 4);
            return (tmp[0] & 0xFF) | ((tmp[1] & 0xFF) << 8) | ((tmp[2] & 0xFF) << 16) | ((tmp[3] & 0xFF) << 24);
        }

        long i64() throws IOException {
            fully(tmp, 8);
            long v = 0;
            for (int i = 7; i >= 0; i--) v = (v << 8) | (tmp[i] & 0xFF);
            return v;
        }

        String string() throws IOException {
            long n = i64();
            if (n < 0 || n > 1 << 26) throw new IOException("Cadena GGUF de longitud invalida: " + n);
            byte[] b = new byte[(int) n];
            fully(b, (int) n);
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    /** Comprobacion barata: 4 bytes de magia y version 2 o 3. */
    static boolean looksLikeGguf(File f) {
        if (f == null || !f.isFile() || f.length() < 24) return false;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b = new byte[8];
            int off = 0;
            while (off < 8) {
                int r = in.read(b, off, 8 - off);
                if (r < 0) return false;
                off += r;
            }
            boolean magic = b[0] == 'G' && b[1] == 'G' && b[2] == 'U' && b[3] == 'F';
            int ver = (b[4] & 0xFF) | ((b[5] & 0xFF) << 8) | ((b[6] & 0xFF) << 16) | ((b[7] & 0xFF) << 24);
            return magic && (ver == 2 || ver == 3);
        } catch (IOException e) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * Abre y analiza el archivo. Los tipos de tensor no admitidos se rechazan aqui con un mensaje claro.
     *
     * @param mapData si es false solo se leen metadatos (archivos de vocabulario sin tensores).
     */
    Gguf(File file, boolean mapData) throws IOException {
        this.file = file;
        this.fileSize = file.length();
        InputStream fin = new BufferedInputStream(new FileInputStream(file), 1 << 16);
        try {
            Reader r = new Reader(fin);
            int magic = r.i32();
            if (magic != MAGIC) throw new IOException("No es un archivo GGUF (magia incorrecta)");
            int ver = r.i32();
            if ((ver & 0xFFFF) == 0 && ver != 0) throw new IOException("Archivo GGUF big-endian, no admitido");
            if (ver != 2 && ver != 3) throw new IOException("Version GGUF no admitida: " + ver);
            version = ver;
            long nTensors = r.i64();
            long nKv = r.i64();
            if (nTensors < 0 || nTensors > 1 << 20 || nKv < 0 || nKv > 1 << 20) throw new IOException("Cabecera GGUF invalida");
            for (long i = 0; i < nKv; i++) {
                String key = r.string();
                int type = r.i32();
                meta.put(key, readValue(r, type));
            }
            int align = getInt("general.alignment", DEFAULT_ALIGNMENT);
            if (align <= 0 || (align & (align - 1)) != 0) throw new IOException("general.alignment invalido: " + align);
            alignment = align;
            List<String> unsupported = new ArrayList<>();
            for (long i = 0; i < nTensors; i++) {
                String name = r.string();
                int nd = r.i32();
                if (nd < 1 || nd > 4) throw new IOException("Tensor con " + nd + " dimensiones: " + name);
                long[] dims = new long[nd];
                for (int d = 0; d < nd; d++) dims[d] = r.i64();
                int type = r.i32();
                long offset = r.i64();
                if (!Quant.supported(type)) {
                    unsupported.add(name + " (" + Quant.name(type) + ")");
                    continue;
                }
                Tensor t = new Tensor(name, dims, type, offset);
                tensors.put(name, t);
                tensorList.add(t);
            }
            if (!unsupported.isEmpty()) {
                String first = unsupported.get(0);
                throw new IOException("Tipo de cuantizacion no admitido: " + first
                        + (unsupported.size() > 1 ? " y " + (unsupported.size() - 1) + " tensores mas" : "")
                        + ". Se admiten F32, F16, BF16, Q4_0, Q4_1, Q5_0, Q5_1, Q8_0, Q4_K, Q5_K y Q6_K.");
            }
            dataOffset = r.pos + (alignment - r.pos % alignment) % alignment;
        } finally {
            fin.close();
        }
        for (Tensor t : tensorList) {
            if (dataOffset + t.offset + t.nbytes > fileSize) {
                throw new IOException("El tensor " + t.name + " sobrepasa el final del archivo (archivo truncado)");
            }
        }
        if (mapData && !tensorList.isEmpty()) map();
    }

    private Object readValue(Reader r, int type) throws IOException {
        switch (type) {
            case T_UINT8: return r.u8();
            case T_INT8: return (int) (byte) r.u8();
            case T_UINT16: return r.u16();
            case T_INT16: return (int) (short) r.u16();
            case T_UINT32: {
                int v = r.i32();
                return v >= 0 ? (Object) v : (Object) (v & 0xFFFFFFFFL);
            }
            case T_INT32: return r.i32();
            case T_FLOAT32: return Float.intBitsToFloat(r.i32());
            case T_BOOL: return r.u8() != 0;
            case T_STRING: return r.string();
            case T_UINT64:
            case T_INT64: return r.i64();
            case T_FLOAT64: return Double.longBitsToDouble(r.i64());
            case T_ARRAY: {
                int et = r.i32();
                long n = r.i64();
                if (n < 0 || n > 1 << 24) throw new IOException("Array de metadatos demasiado grande: " + n);
                int count = (int) n;
                switch (et) {
                    case T_STRING: {
                        String[] a = new String[count];
                        for (int i = 0; i < count; i++) a[i] = r.string();
                        return a;
                    }
                    case T_FLOAT32: {
                        float[] a = new float[count];
                        for (int i = 0; i < count; i++) a[i] = Float.intBitsToFloat(r.i32());
                        return a;
                    }
                    case T_UINT8: case T_INT8: case T_UINT16: case T_INT16: case T_UINT32: case T_INT32: {
                        int[] a = new int[count];
                        for (int i = 0; i < count; i++) a[i] = ((Number) readValue(r, et)).intValue();
                        return a;
                    }
                    case T_UINT64: case T_INT64: {
                        long[] a = new long[count];
                        for (int i = 0; i < count; i++) a[i] = r.i64();
                        return a;
                    }
                    case T_BOOL: {
                        boolean[] a = new boolean[count];
                        for (int i = 0; i < count; i++) a[i] = r.u8() != 0;
                        return a;
                    }
                    default: {
                        Object[] a = new Object[count];
                        for (int i = 0; i < count; i++) a[i] = readValue(r, et);
                        return a;
                    }
                }
            }
            default:
                throw new IOException("Tipo de metadato GGUF desconocido: " + type);
        }
    }

    /** Mapea un tramo del archivo; sin espacio de direcciones (procesos de 32 bits) avisa con MAPFAIL. */
    private MappedByteBuffer mapSegment(long offset, long len) throws IOException {
        try {
            return channel.map(FileChannel.MapMode.READ_ONLY, offset, len);
        } catch (IOException e) {
            throw new IOException("MAPFAIL: sin memoria de direcciones para mapear el modelo", e);
        }
    }

    private void map() throws IOException {
        raf = new RandomAccessFile(file, "r");
        channel = raf.getChannel();
        long dataLen = fileSize - dataOffset;
        if (dataLen <= Integer.MAX_VALUE) {
            segments = new MappedByteBuffer[]{mapSegment(dataOffset, dataLen)};
            for (Tensor t : tensorList) {
                t.segment = 0;
                t.segmentOffset = (int) t.offset;
            }
        } else {
            segments = new MappedByteBuffer[tensorList.size()];
            for (int i = 0; i < tensorList.size(); i++) {
                Tensor t = tensorList.get(i);
                if (t.nbytes > Integer.MAX_VALUE) throw new IOException("Tensor de mas de 2 GB: " + t.name);
                segments[i] = mapSegment(dataOffset + t.offset, t.nbytes);
                t.segment = i;
                t.segmentOffset = 0;
            }
        }
        for (MappedByteBuffer b : segments) b.order(ByteOrder.LITTLE_ENDIAN);
    }

    int segmentCount() {
        return segments == null ? 0 : segments.length;
    }

    /** Duplicado independiente (posicion propia) de un segmento, uno por hilo. */
    ByteBuffer duplicate(int segment) {
        ByteBuffer d = segments[segment].duplicate();
        d.order(ByteOrder.LITTLE_ENDIAN);
        return d;
    }

    // ----- acceso a metadatos -----

    boolean has(String key) {
        return meta.containsKey(key);
    }

    Object get(String key) {
        return meta.get(key);
    }

    String getString(String key, String def) {
        Object v = meta.get(key);
        return v instanceof String ? (String) v : def;
    }

    /** Entero de cualquier tipo numerico (uint32, uint64, int32...). */
    int getInt(String key, int def) {
        Object v = meta.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
        return def;
    }

    long getLong(String key, long def) {
        Object v = meta.get(key);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    float getFloat(String key, float def) {
        Object v = meta.get(key);
        return v instanceof Number ? ((Number) v).floatValue() : def;
    }

    boolean getBool(String key, boolean def) {
        Object v = meta.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return def;
    }

    String[] getStrings(String key) {
        Object v = meta.get(key);
        return v instanceof String[] ? (String[]) v : null;
    }

    int[] getInts(String key) {
        Object v = meta.get(key);
        if (v instanceof int[]) return (int[]) v;
        if (v instanceof long[]) {
            long[] l = (long[]) v;
            int[] a = new int[l.length];
            for (int i = 0; i < l.length; i++) a[i] = (int) l[i];
            return a;
        }
        return null;
    }

    float[] getFloats(String key) {
        Object v = meta.get(key);
        return v instanceof float[] ? (float[]) v : null;
    }

    Tensor tensor(String name) {
        return tensors.get(name);
    }

    Tensor require(String name) throws IOException {
        Tensor t = tensors.get(name);
        if (t == null) throw new IOException("Falta el tensor " + name);
        return t;
    }

    @Override
    public void close() {
        segments = null;
        try {
            if (channel != null) channel.close();
            if (raf != null) raf.close();
        } catch (IOException ignored) {
        }
        channel = null;
        raf = null;
    }
}
