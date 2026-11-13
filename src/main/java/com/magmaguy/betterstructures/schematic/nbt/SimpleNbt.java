package com.magmaguy.betterstructures.schematic.nbt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * A lightweight, zero-dependency, pure-Java NBT parser designed specifically for Minecraft
 * schematic and structure files (.schem). Thread-safe and fast.
 */
public final class SimpleNbt {

    public static final byte TAG_END = 0;
    public static final byte TAG_BYTE = 1;
    public static final byte TAG_SHORT = 2;
    public static final byte TAG_INT = 3;
    public static final byte TAG_LONG = 4;
    public static final byte TAG_FLOAT = 5;
    public static final byte TAG_DOUBLE = 6;
    public static final byte TAG_BYTE_ARRAY = 7;
    public static final byte TAG_STRING = 8;
    public static final byte TAG_LIST = 9;
    public static final byte TAG_COMPOUND = 10;
    public static final byte TAG_INT_ARRAY = 11;
    public static final byte TAG_LONG_ARRAY = 12;

    private SimpleNbt() {
    }

    public interface Tag {
        byte getId();
        Object getValue();
    }

    public static final class ByteTag implements Tag {
        private final byte value;
        public ByteTag(byte value) { this.value = value; }
        @Override public byte getId() { return TAG_BYTE; }
        @Override public Byte getValue() { return value; }
        public byte asByte() { return value; }
        @Override public String toString() { return value + "b"; }
    }

    public static final class ShortTag implements Tag {
        private final short value;
        public ShortTag(short value) { this.value = value; }
        @Override public byte getId() { return TAG_SHORT; }
        @Override public Short getValue() { return value; }
        public short asShort() { return value; }
        @Override public String toString() { return value + "s"; }
    }

    public static final class IntTag implements Tag {
        private final int value;
        public IntTag(int value) { this.value = value; }
        @Override public byte getId() { return TAG_INT; }
        @Override public Integer getValue() { return value; }
        public int asInt() { return value; }
        @Override public String toString() { return Integer.toString(value); }
    }

    public static final class LongTag implements Tag {
        private final long value;
        public LongTag(long value) { this.value = value; }
        @Override public byte getId() { return TAG_LONG; }
        @Override public Long getValue() { return value; }
        public long asLong() { return value; }
        @Override public String toString() { return value + "L"; }
    }

    public static final class FloatTag implements Tag {
        private final float value;
        public FloatTag(float value) { this.value = value; }
        @Override public byte getId() { return TAG_FLOAT; }
        @Override public Float getValue() { return value; }
        public float asFloat() { return value; }
        @Override public String toString() { return value + "f"; }
    }

    public static final class DoubleTag implements Tag {
        private final double value;
        public DoubleTag(double value) { this.value = value; }
        @Override public byte getId() { return TAG_DOUBLE; }
        @Override public Double getValue() { return value; }
        public double asDouble() { return value; }
        @Override public String toString() { return value + "d"; }
    }

    public static final class ByteArrayTag implements Tag {
        private final byte[] value;
        public ByteArrayTag(byte[] value) { this.value = value; }
        @Override public byte getId() { return TAG_BYTE_ARRAY; }
        @Override public byte[] getValue() { return value; }
        public byte[] asByteArray() { return value; }
        @Override public String toString() { return "[" + value.length + " bytes]"; }
    }

    public static final class StringTag implements Tag {
        private final String value;
        public StringTag(String value) { this.value = value != null ? value : ""; }
        @Override public byte getId() { return TAG_STRING; }
        @Override public String getValue() { return value; }
        public String asString() { return value; }
        @Override public String toString() { return "\"" + value + "\""; }
    }

    public static final class ListTag implements Tag, Iterable<Tag> {
        private final byte elementTypeId;
        private final List<Tag> list;

        public ListTag(byte elementTypeId, List<Tag> list) {
            this.elementTypeId = elementTypeId;
            this.list = list != null ? list : new ArrayList<>();
        }

        @Override public byte getId() { return TAG_LIST; }
        @Override public List<Tag> getValue() { return list; }
        public byte getElementTypeId() { return elementTypeId; }
        public int size() { return list.size(); }
        public Tag get(int index) { return list.get(index); }
        @Override public Iterator<Tag> iterator() { return list.iterator(); }
        @Override public String toString() { return list.toString(); }
    }

    public static final class CompoundTag implements Tag {
        private final Map<String, Tag> map;

        public CompoundTag() {
            this.map = new LinkedHashMap<>();
        }

        public CompoundTag(Map<String, Tag> map) {
            this.map = map != null ? map : new LinkedHashMap<>();
        }

        @Override public byte getId() { return TAG_COMPOUND; }
        @Override public Map<String, Tag> getValue() { return map; }

        public void put(String key, Tag tag) {
            if (key != null && tag != null) map.put(key, tag);
        }

        public Tag get(String key) {
            return map.get(key);
        }

        public boolean containsKey(String key) {
            return map.containsKey(key);
        }

        public Set<String> keySet() {
            return map.keySet();
        }

        public Collection<Tag> values() {
            return map.values();
        }

        public String getString(String key, String def) {
            Tag tag = map.get(key);
            if (tag instanceof StringTag stringTag) return stringTag.asString();
            return def;
        }

        public String getString(String key) {
            return getString(key, "");
        }

        public int getInt(String key, int def) {
            Tag tag = map.get(key);
            if (tag instanceof IntTag intTag) return intTag.asInt();
            if (tag instanceof ShortTag shortTag) return shortTag.asShort();
            if (tag instanceof ByteTag byteTag) return byteTag.asByte();
            return def;
        }

        public int getInt(String key) {
            return getInt(key, 0);
        }

        public short getShort(String key, short def) {
            Tag tag = map.get(key);
            if (tag instanceof ShortTag shortTag) return shortTag.asShort();
            if (tag instanceof IntTag intTag) return (short) intTag.asInt();
            if (tag instanceof ByteTag byteTag) return (short) byteTag.asByte();
            return def;
        }

        public short getShort(String key) {
            return getShort(key, (short) 0);
        }

        public byte getByte(String key, byte def) {
            Tag tag = map.get(key);
            if (tag instanceof ByteTag byteTag) return byteTag.asByte();
            return def;
        }

        public byte getByte(String key) {
            return getByte(key, (byte) 0);
        }

        public byte[] getByteArray(String key) {
            Tag tag = map.get(key);
            if (tag instanceof ByteArrayTag byteArrayTag) return byteArrayTag.asByteArray();
            return null;
        }

        public int[] getIntArray(String key) {
            Tag tag = map.get(key);
            if (tag instanceof IntArrayTag intArrayTag) return intArrayTag.asIntArray();
            return null;
        }

        public CompoundTag getCompound(String key) {
            Tag tag = map.get(key);
            if (tag instanceof CompoundTag compoundTag) return compoundTag;
            return null;
        }

        public ListTag getList(String key) {
            Tag tag = map.get(key);
            if (tag instanceof ListTag listTag) return listTag;
            return null;
        }

        @Override public String toString() { return map.toString(); }
    }

    public static final class IntArrayTag implements Tag {
        private final int[] value;
        public IntArrayTag(int[] value) { this.value = value; }
        @Override public byte getId() { return TAG_INT_ARRAY; }
        @Override public int[] getValue() { return value; }
        public int[] asIntArray() { return value; }
        @Override public String toString() { return Arrays.toString(value); }
    }

    public static final class LongArrayTag implements Tag {
        private final long[] value;
        public LongArrayTag(long[] value) { this.value = value; }
        @Override public byte getId() { return TAG_LONG_ARRAY; }
        @Override public long[] getValue() { return value; }
        public long[] asLongArray() { return value; }
        @Override public String toString() { return Arrays.toString(value); }
    }

    /**
     * Reads a root CompoundTag from a file, auto-detecting GZIP compression.
     */
    public static CompoundTag readFile(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return read(in);
        }
    }

    /**
     * Reads a root CompoundTag from an input stream, auto-detecting GZIP compression.
     */
    public static CompoundTag read(InputStream inputStream) throws IOException {
        InputStream stream = inputStream;
        if (!stream.markSupported()) {
            stream = new BufferedInputStream(stream);
        }
        stream.mark(2);
        int b1 = stream.read();
        int b2 = stream.read();
        stream.reset();

        if (b1 == 0x1f && b2 == 0x8b) {
            stream = new GZIPInputStream(stream);
        }

        DataInputStream dis = new DataInputStream(stream);
        byte type = dis.readByte();
        if (type == TAG_END) {
            return new CompoundTag();
        }
        if (type != TAG_COMPOUND) {
            throw new IOException("Root tag must be a named CompoundTag, but found type " + type);
        }
        // Read root tag name (usually empty or "Schematic")
        readString(dis);
        return readCompoundPayload(dis);
    }

    private static Tag readTagPayload(byte type, DataInputStream dis) throws IOException {
        return switch (type) {
            case TAG_BYTE -> new ByteTag(dis.readByte());
            case TAG_SHORT -> new ShortTag(dis.readShort());
            case TAG_INT -> new IntTag(dis.readInt());
            case TAG_LONG -> new LongTag(dis.readLong());
            case TAG_FLOAT -> new FloatTag(dis.readFloat());
            case TAG_DOUBLE -> new DoubleTag(dis.readDouble());
            case TAG_BYTE_ARRAY -> {
                int length = dis.readInt();
                if (length < 0) throw new IOException("Negative byte array length: " + length);
                byte[] bytes = new byte[length];
                dis.readFully(bytes);
                yield new ByteArrayTag(bytes);
            }
            case TAG_STRING -> new StringTag(readString(dis));
            case TAG_LIST -> {
                byte elemType = dis.readByte();
                int length = dis.readInt();
                if (length < 0) throw new IOException("Negative list length: " + length);
                List<Tag> list = new ArrayList<>(Math.min(length, 1024));
                for (int i = 0; i < length; i++) {
                    list.add(readTagPayload(elemType, dis));
                }
                yield new ListTag(elemType, list);
            }
            case TAG_COMPOUND -> readCompoundPayload(dis);
            case TAG_INT_ARRAY -> {
                int length = dis.readInt();
                if (length < 0) throw new IOException("Negative int array length: " + length);
                int[] ints = new int[length];
                for (int i = 0; i < length; i++) ints[i] = dis.readInt();
                yield new IntArrayTag(ints);
            }
            case TAG_LONG_ARRAY -> {
                int length = dis.readInt();
                if (length < 0) throw new IOException("Negative long array length: " + length);
                long[] longs = new long[length];
                for (int i = 0; i < length; i++) longs[i] = dis.readLong();
                yield new LongArrayTag(longs);
            }
            default -> throw new IOException("Unknown tag type: " + type);
        };
    }

    private static CompoundTag readCompoundPayload(DataInputStream dis) throws IOException {
        Map<String, Tag> map = new LinkedHashMap<>();
        while (true) {
            byte type = dis.readByte();
            if (type == TAG_END) break;
            String name = readString(dis);
            Tag tag = readTagPayload(type, dis);
            map.put(name, tag);
        }
        return new CompoundTag(map);
    }

    private static String readString(DataInputStream dis) throws IOException {
        int length = dis.readUnsignedShort();
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
