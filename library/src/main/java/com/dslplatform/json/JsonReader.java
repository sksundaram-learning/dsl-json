package com.dslplatform.json;

import javax.management.ReflectionException;
import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.*;

/**
 * Object for processing JSON from byte[] and InputStream.
 * DSL-JSON works on byte level (instead of char level).
 * Deserialized instances can obtain TContext information provided with this reader.
 * <p>
 * JsonReader can be reused by calling process methods.
 *
 * @param <TContext> context passed to deserialized object instances
 */
public final class JsonReader<TContext> {

	private static final boolean[] WHITESPACE = new boolean[256];

	static {
		WHITESPACE[9 + 128] = true;
		WHITESPACE[10 + 128] = true;
		WHITESPACE[11 + 128] = true;
		WHITESPACE[12 + 128] = true;
		WHITESPACE[13 + 128] = true;
		WHITESPACE[32 + 128] = true;
		WHITESPACE[-96 + 128] = true;
		WHITESPACE[-31 + 128] = true;
		WHITESPACE[-30 + 128] = true;
		WHITESPACE[-29 + 128] = true;
	}

	private int tokenStart;
	private int nameEnd;
	private int currentIndex = 0;
	private long currentPosition = 0;
	private byte last = ' ';

	private int length;
	private final char[] tmp;

	public final TContext context;
	protected byte[] buffer;
	protected char[] chars;

	private InputStream stream;
	private int readLimit;
	//always leave some room for reading special stuff, so that buffer contains enough padding for such optimizations
	private int bufferLenWithExtraSpace;

	private final StringCache keyCache;
	private final StringCache valuesCache;
	private final HashMap<Type, ReadObject<?>> readers;
	private final HashMap<Type, BindObject<?>> binders;

	private JsonReader(
			final char[] tmp,
			final byte[] buffer,
			final int length,
			final TContext context,
			final StringCache keyCache,
			final StringCache valuesCache,
			final HashMap<Type, ReadObject<?>> readers,
			final HashMap<Type, BindObject<?>> binders) {
		this.tmp = tmp;
		this.buffer = buffer;
		this.length = length;
		this.bufferLenWithExtraSpace = buffer.length - 38; //currently maximum padding is for uuid
		this.context = context;
		this.chars = tmp;
		this.keyCache = keyCache;
		this.valuesCache = valuesCache;
		this.readers = readers;
		this.binders = binders;
	}

	/**
	 * Prefer creating reader through DslJson#newReader since it will pass several arguments (such as key/string value cache)
	 * First byte will not be read.
	 * It will allocate new char[64] for string buffer.
	 * Key and string vales cache will be null.
	 *
	 * @param buffer input JSON
	 * @param context context
	 */
	@Deprecated
	public JsonReader(final byte[] buffer, final TContext context) {
		this(new char[64], buffer, buffer.length, context, null, null, new HashMap<Type, ReadObject<?>>(0), new HashMap<Type, BindObject<?>>(0));
	}

	@Deprecated
	public JsonReader(final byte[] buffer, final TContext context, StringCache keyCache, StringCache valuesCache) {
		this(new char[64], buffer, buffer.length, context, keyCache, valuesCache, new HashMap<Type, ReadObject<?>>(0), new HashMap<Type, BindObject<?>>(0));
	}

	@Deprecated
	public JsonReader(final byte[] buffer, final TContext context, final char[] tmp) {
		this(tmp, buffer, buffer.length, context, null, null, new HashMap<Type, ReadObject<?>>(0), new HashMap<Type, BindObject<?>>(0));
		if (tmp == null) {
			throw new IllegalArgumentException("tmp buffer provided as null.");
		}
	}

	@Deprecated
	public JsonReader(final byte[] buffer, final int length, final TContext context) {
		this(buffer, length, context, new char[64]);
	}

	@Deprecated
	public JsonReader(final byte[] buffer, final int length, final TContext context, final char[] tmp) {
		this(buffer, length, context, tmp, null, null);
	}

	@Deprecated
	public JsonReader(final byte[] buffer, final int length, final TContext context, final char[] tmp, final StringCache keyCache, final StringCache valuesCache) {
		this(tmp, buffer, length, context, keyCache, valuesCache, new HashMap<Type, ReadObject<?>>(0), new HashMap<Type, BindObject<?>>(0));
		if (tmp == null) {
			throw new IllegalArgumentException("tmp buffer provided as null.");
		}
		if (length > buffer.length) {
			throw new IllegalArgumentException("length can't be longer than buffer.length");
		} else if (length < buffer.length) {
			buffer[length] = '\0';
		}
	}

	JsonReader(
			final byte[] buffer,
			final int length,
			final TContext context,
			final char[] tmp,
			final StringCache keyCache,
			final StringCache valuesCache,
			final HashMap<Type, ReadObject<?>> readers,
			final HashMap<Type, BindObject<?>> binders) {
		this(tmp, buffer, length, context, keyCache, valuesCache, readers, binders);
		if (tmp == null) {
			throw new IllegalArgumentException("tmp buffer provided as null.");
		}
		if (length > buffer.length) {
			throw new IllegalArgumentException("length can't be longer than buffer.length");
		} else if (length < buffer.length) {
			buffer[length] = '\0';
		}
	}


	/**
	 * Will be removed. Exists only for backward compatibility
	 * @param stream process stream
	 * @throws IOException error reading from stream
	 */
	@Deprecated
	public final void reset(final InputStream stream) throws IOException {
		process(stream);
	}

	/**
	 * Will be removed. Exists only for backward compatibility
	 * @param size size of byte[] input to use
	 */
	@Deprecated
	final void reset(final int size) {
		process(null, size);
	}

	/**
	 * Bind input stream for processing.
	 * Stream will be processed in byte[] chunks.
	 * If stream is null, reference to stream will be released.
	 *
	 * @param stream set input stream
	 * @return itself
	 * @throws IOException unable to read from stream
	 */
	public final JsonReader<TContext> process(final InputStream stream) throws IOException {
		this.currentPosition = 0;
		this.currentIndex = 0;
		this.stream = stream;
		if (stream != null) {
			this.readLimit = this.length < bufferLenWithExtraSpace ? this.length : bufferLenWithExtraSpace;
			final int available = readFully(buffer, stream, 0);
			readLimit = available < bufferLenWithExtraSpace ? available : bufferLenWithExtraSpace;
			this.length = available;
		}
		return this;
	}

	/**
	 * Bind byte[] buffer for processing.
	 * If this method is used in combination with process(InputStream) this buffer will be used for processing chunks of stream.
	 * If null is sent for byte[] buffer, new length for valid input will be set for existing buffer.
	 *
	 * @param newBuffer new buffer to use for processing
	 * @param newLength length of buffer which can be used
	 * @return itself
	 */
	public final JsonReader<TContext> process(final byte[] newBuffer, final int newLength) {
		if (newBuffer != null) {
			this.buffer = newBuffer;
			this.bufferLenWithExtraSpace = buffer.length - 38; //currently maximum padding is for uuid
		}
		if (newLength > buffer.length) {
			throw new IllegalArgumentException("length can't be longer than buffer.length");
		}
		currentIndex = 0;
		this.length = newLength;
		this.stream = null;
		return this;
	}

	/**
	 * Valid length of the input buffer.
	 *
	 * @return size of JSON input
	 */
	public final int length() {
		return length;
	}

	private final static Charset UTF_8 = Charset.forName("UTF-8");

	@Override
	public String toString() {
		return new String(buffer, 0, length, UTF_8);
	}

	private static int readFully(final byte[] buffer, final InputStream stream, final int offset) throws IOException {
		int read;
		int position = offset;
		while (position < buffer.length
				&& (read = stream.read(buffer, position, buffer.length - position)) != -1) {
			position += read;
		}
		return position;
	}

	/**
	 * Read next byte from the JSON input.
	 * If buffer has been read in full IOException will be thrown
	 *
	 * @return next byte
	 * @throws IOException when end of JSON input
	 */
	public final byte read() throws IOException {
		if (stream != null && currentIndex > readLimit) {
			final int len = length - currentIndex;
			System.arraycopy(buffer, currentIndex, buffer, 0, len);
			final int available = readFully(buffer, stream, len);
			currentPosition += currentIndex;
			if (available == len) {
				readLimit = length - currentIndex;
				length = readLimit;
				currentIndex = 0;
			} else {
				readLimit = available < bufferLenWithExtraSpace ? available : bufferLenWithExtraSpace;
				this.length = available;
				currentIndex = 0;
			}
		}
		if (currentIndex >= length) {
			throw new IOException("Unexpected end of JSON input");
		}
		return last = buffer[currentIndex++];
	}

	final boolean isEndOfStream() throws IOException {
		if (stream == null) {
			return length == currentIndex;
		}
		if (length != currentIndex) {
			return false;
		}
		final int len = buffer.length - currentIndex;
		System.arraycopy(buffer, currentIndex, buffer, 0, len);
		int position = readFully(buffer, stream, len);
		if (position == len) {
			return true;
		}
		currentPosition += currentIndex;
		length = position;
		currentIndex = 0;
		return position == 0;
	}

	/**
	 * Which was last byte read from the JSON input.
	 * JsonReader doesn't allow to go back, but it remembers previously read byte
	 *
	 * @return which was the last byte read
	 */
	public final byte last() {
		return last;
	}

	final IOException expecting(final String what) {
		return new IOException("Expecting '" + what + "' at position " + positionInStream() + ". Found " + (char) last);
	}

	final IOException expecting(final String what, final byte found) {
		return new IOException("Expecting '" + what + "' at position " + positionInStream() + ". Found " + (char) found);
	}

	public final int getTokenStart() {
		return tokenStart;
	}

	public final int getCurrentIndex() {
		return currentIndex;
	}

	/**
	 * will be removed. not used anymore
	 *
	 * @return parsed chars from a number
	 */
	@Deprecated
	public final char[] readNumber() {
		tokenStart = currentIndex - 1;
		tmp[0] = (char) last;
		int i = 1;
		int ci = currentIndex;
		byte bb = last;
		while (i < tmp.length && ci < length) {
			bb = buffer[ci++];
			if (bb == ',' || bb == '}' || bb == ']') break;
			tmp[i++] = (char) bb;
		}
		currentIndex += i - 1;
		last = bb;
		return tmp;
	}

	public final int scanNumber() {
		tokenStart = currentIndex - 1;
		int i = 1;
		int ci = currentIndex;
		byte bb = last;
		while (ci < length) {
			bb = buffer[ci++];
			if (bb == ',' || bb == '}' || bb == ']') break;
			i++;
		}
		currentIndex += i - 1;
		last = bb;
		return tokenStart;
	}

	final char[] prepareBuffer(final int start) {
		final int remaining = length - start;
		while (chars.length < remaining) {
			chars = Arrays.copyOf(chars, chars.length * 2);
		}
		final char[] _tmp = chars;
		final byte[] _buf = buffer;
		for (int i = 0; i < remaining; i++) {
			_tmp[i] = (char) _buf[start + i];
		}
		return _tmp;
	}

	final boolean allWhitespace(final int start, final int end) {
		final byte[] _buf = buffer;
		for (int i = start; i < end; i++) {
			if (!WHITESPACE[_buf[i] + 128]) return false;
		}
		return true;
	}

	final int findNonWhitespace(final int end) {
		final byte[] _buf = buffer;
		for (int i = end - 1; i > 0; i--) {
			if (!WHITESPACE[_buf[i] + 128]) return i + 1;
		}
		return 0;
	}

	/**
	 * Read simple ascii string. Will not use values cache to create instance.
	 *
	 * @return parsed string
	 * @throws IOException unable to parse string
	 */
	public final String readSimpleString() throws IOException {
		if (last != '"') {
			throw new IOException("Expecting '\"' at position " + positionInStream() + ". Found " + (char) last);
		}
		int i = 0;
		int ci = currentIndex;
		try {
			while (i < tmp.length) {
				final byte bb = buffer[ci++];
				if (bb == '"') break;
				tmp[i++] = (char) bb;
			}
		} catch (ArrayIndexOutOfBoundsException ignore) {
			throw new IOException("JSON string was not closed with a double quote at: " + positionInStream());
		}
		if (ci > length) {
			throw new IOException("JSON string was not closed with a double quote at: " + (currentPosition + length));
		}
		currentIndex = ci;
		return new String(tmp, 0, i);
	}

	/**
	 * Read simple "ascii string" into temporary buffer.
	 * String length must be obtained through getTokenStart and getCurrentToken
	 *
	 * @return temporary buffer
	 * @throws IOException unable to parse string
	 */
	public final char[] readSimpleQuote() throws IOException {
		if (last != '"') {
			throw new IOException("Expecting '\"' at position " + positionInStream() + ". Found " + (char) last);
		}
		int ci = tokenStart = currentIndex;
		try {
			for (int i = 0; i < tmp.length; i++) {
				final byte bb = buffer[ci++];
				if (bb == '"') break;
				tmp[i] = (char) bb;
			}
		} catch (ArrayIndexOutOfBoundsException ignore) {
			throw new IOException("JSON string was not closed with a double quote at: " + positionInStream());
		}
		if (ci > length) {
			throw new IOException("JSON string was not closed with a double quote at: " + (currentPosition + length));
		}
		currentIndex = ci;
		return tmp;
	}

	/**
	 * Read string from JSON input.
	 * If values cache is used, string will be looked up from the cache.
	 * <p>
	 * String value must start and end with a double quote (").
	 *
	 * @return parsed string
	 * @throws IOException error reading string input
	 */
	public final String readString() throws IOException {
		final int len = parseString();
		return valuesCache == null ? new String(chars, 0, len) : valuesCache.get(chars, len);
	}

	final int parseString() throws IOException {
		final int startIndex = currentIndex;
		if (last != '"') {
			//TODO: count special chars in separate counter
			throw new IOException("JSON string must start with a double quote at: " + positionInStream());
		}

		byte bb;
		int ci = currentIndex;
		char[] _tmp = chars;
		final int remaining = length - currentIndex;
		int _tmpLen = _tmp.length < remaining ? _tmp.length : remaining;
		int i = 0;
		while (i < _tmpLen) {
			bb = buffer[ci++];
			if (bb == '"') {
				currentIndex = ci;
				return i;
			}
			// If we encounter a backslash, which is a beginning of an escape sequence
			// or a high bit was set - indicating an UTF-8 encoded multibyte character,
			// there is no chance that we can decode the string without instantiating
			// a temporary buffer, so quit this loop
			if ((bb ^ '\\') < 1) break;
			_tmp[i++] = (char) bb;
		}
		if (i == _tmp.length) {
			_tmp = chars = Arrays.copyOf(chars, chars.length * 2);
		}
		currentIndex = ci;
		int soFar = --currentIndex - startIndex;

		while (!isEndOfStream()) {
			int bc = read();
			if (bc == '"') {
				return soFar;
			}

			if (bc == '\\') {
				if (soFar >= _tmpLen - 6) {
					_tmp = chars = Arrays.copyOf(chars, chars.length * 2);
					_tmpLen = _tmp.length;
				}
				bc = buffer[currentIndex++];

				switch (bc) {
					case 'b':
						bc = '\b';
						break;
					case 't':
						bc = '\t';
						break;
					case 'n':
						bc = '\n';
						break;
					case 'f':
						bc = '\f';
						break;
					case 'r':
						bc = '\r';
						break;
					case '"':
					case '/':
					case '\\':
						break;
					case 'u':
						bc = (hexToInt(buffer[currentIndex++]) << 12) +
								(hexToInt(buffer[currentIndex++]) << 8) +
								(hexToInt(buffer[currentIndex++]) << 4) +
								hexToInt(buffer[currentIndex++]);
						break;

					default:
						throw new IOException("Could not parse String at position: " + positionInStream() + ". Invalid escape combination detected: '\\" + bc + "'");
				}
			} else if ((bc & 0x80) != 0) {
				if (soFar >= _tmpLen - 4) {
					_tmp = chars = Arrays.copyOf(chars, chars.length * 2);
					_tmpLen = _tmp.length;
				}
				final int u2 = buffer[currentIndex++];
				if ((bc & 0xE0) == 0xC0) {
					bc = ((bc & 0x1F) << 6) + (u2 & 0x3F);
				} else {
					final int u3 = buffer[currentIndex++];
					if ((bc & 0xF0) == 0xE0) {
						bc = ((bc & 0x0F) << 12) + ((u2 & 0x3F) << 6) + (u3 & 0x3F);
					} else {
						final int u4 = buffer[currentIndex++];
						if ((bc & 0xF8) == 0xF0) {
							bc = ((bc & 0x07) << 18) + ((u2 & 0x3F) << 12) + ((u3 & 0x3F) << 6) + (u4 & 0x3F);
						} else {
							// there are legal 5 & 6 byte combinations, but none are _valid_
							throw new IOException("Invalid unicode character detected at: " + positionInStream());
						}

						if (bc >= 0x10000) {
							// check if valid unicode
							if (bc >= 0x110000) {
								throw new IOException("Invalid unicode character detected at: " + positionInStream());
							}

							// split surrogates
							final int sup = bc - 0x10000;
							_tmp[soFar++] = (char) ((sup >>> 10) + 0xd800);
							_tmp[soFar++] = (char) ((sup & 0x3ff) + 0xdc00);
						}
					}
				}
			} else if (soFar >= _tmpLen) {
				_tmp = chars = Arrays.copyOf(chars, chars.length * 2);
				_tmpLen = _tmp.length;
			}

			_tmp[soFar++] = (char) bc;
		}
		throw new IOException("JSON string was not closed with a double quote at: " + positionInStream());
	}

	private static int hexToInt(final byte value) throws IOException {
		if (value >= '0' && value <= '9') return value - 0x30;
		if (value >= 'A' && value <= 'F') return value - 0x37;
		if (value >= 'a' && value <= 'f') return value - 0x57;
		throw new IOException("Could not parse unicode escape, expected a hexadecimal digit, got '" + value + "'");
	}

	private boolean wasWhiteSpace() {
		switch (last) {
			case 9:
			case 10:
			case 11:
			case 12:
			case 13:
			case 32:
			case -96:
				return true;
			case -31:
				if (currentIndex + 1 < length && buffer[currentIndex] == -102 && buffer[currentIndex + 1] == -128) {
					currentIndex += 2;
					last = ' ';
					return true;
				}
				return false;
			case -30:
				if (currentIndex + 1 < length) {
					final byte b1 = buffer[currentIndex];
					final byte b2 = buffer[currentIndex + 1];
					if (b1 == -127 && b2 == -97) {
						currentIndex += 2;
						last = ' ';
						return true;
					}
					if (b1 != -128) return false;
					switch (b2) {
						case -128:
						case -127:
						case -126:
						case -125:
						case -124:
						case -123:
						case -122:
						case -121:
						case -120:
						case -119:
						case -118:
						case -88:
						case -87:
						case -81:
							currentIndex += 2;
							last = ' ';
							return true;
						default:
							return false;
					}
				} else {
					return false;
				}
			case -29:
				if (currentIndex + 1 < length && buffer[currentIndex] == -128 && buffer[currentIndex + 1] == -128) {
					currentIndex += 2;
					last = ' ';
					return true;
				}
				return false;
			default:
				return false;
		}
	}

	/**
	 * Read next token (byte) from input JSON.
	 * Whitespace will be skipped and next non-whitespace byte will be returned.
	 *
	 * @return next non-whitespace byte in the JSON input
	 * @throws IOException unable to get next byte (end of stream, ...)
	 */
	public final byte getNextToken() throws IOException {
		read();
		if (WHITESPACE[last + 128]) {
			while (wasWhiteSpace()) {
				read();
			}
		}
		return last;
	}

	public final long positionInStream() {
		return currentPosition + currentIndex;
	}

	public final long positionInStream(final int offset) {
		return currentPosition + currentIndex - offset;
	}

	public final int fillName() throws IOException {
		final int hash = calcHash();
		if (read() != ':') {
			if (!wasWhiteSpace() || getNextToken() != ':') {
				throw new IOException("Expecting ':' at position " + positionInStream() + ". Found " + (char) last);
			}
		}
		return hash;
	}

	public final int calcHash() throws IOException {
		if (last != '"') {
			throw new IOException("Expecting '\"' at position " + positionInStream() + ". Found " + (char) last);
		}
		tokenStart = currentIndex;
		int ci = currentIndex;
		long hash = 0x811c9dc5;
		if (stream != null) {
			while (ci < readLimit) {
				final byte b = buffer[ci];
				if (b == '"') break;
				ci++;
				hash ^= b;
				hash *= 0x1000193;
			}
			if (ci >= readLimit) {
				return calcHashAndCopyName(hash, ci);
			}
			nameEnd = currentIndex = ci + 1;
		} else {
			while (ci < buffer.length) {
				final byte b = buffer[ci++];
				if (b == '"') break;
				hash ^= b;
				hash *= 0x1000193;
			}
			nameEnd = currentIndex = ci;
		}
		return (int) hash;
	}

	private int lastNameLen;

	private int calcHashAndCopyName(long hash, int ci) throws IOException {
		int soFar = ci - tokenStart;
		long startPosition = currentPosition - soFar;
		while (chars.length < soFar) {
			chars = Arrays.copyOf(chars, chars.length * 2);
		}
		int i = 0;
		for (; i < soFar; i++) {
			chars[i] = (char) buffer[i + tokenStart];
		}
		currentIndex = ci;
		do {
			final byte b = read();
			if (b == '"') {
				nameEnd = -1;
				lastNameLen = i;
				return (int) hash;
			}
			if (i == chars.length) {
				chars = Arrays.copyOf(chars, chars.length * 2);
			}
			chars[i++] = (char) b;
			hash ^= b;
			hash *= 0x1000193;
		} while (!isEndOfStream());
		throw new IOException("JSON string was not closed with a double quote at: " + startPosition);
	}

	public final boolean wasLastName(final String name) {
		if (stream != null && nameEnd == -1) {
			if (name.length() != lastNameLen) {
				return false;
			}
			for (int i = 0; i < name.length(); i++) {
				if (name.charAt(i) != chars[i]) {
					return false;
				}
			}
			return true;
		}
		if (name.length() != nameEnd - tokenStart - 1) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			if (name.charAt(i) != buffer[tokenStart + i]) {
				return false;
			}
		}
		return true;
	}

	public final String getLastName() throws IOException {
		if (stream != null && nameEnd == -1) {
			return new String(chars, 0, lastNameLen);
		}
		return new String(buffer, tokenStart, nameEnd - tokenStart - 1, "UTF-8");
	}

	private byte skipString() throws IOException {
		byte c = read();
		byte prev = c;
		boolean inEscape = false;
		while (c != '"' || inEscape) {
			prev = c;
			inEscape = !inEscape && prev == '\\';
			c = read();
		}
		return getNextToken();
	}

	/**
	 * Skip to next non-whitespace token (byte)
	 * Will not allocate memory while skipping over JSON input.
	 *
	 * @return next non-whitespace byte
	 * @throws IOException unable to read next byte (end of stream, invalid JSON, ...)
	 */
	public final byte skip() throws IOException {
		if (last == '"') return skipString();
		if (last == '{') {
			byte nextToken = getNextToken();
			if (nextToken == '}') return getNextToken();
			if (nextToken == '"') {
				nextToken = skipString();
			} else {
				throw new IOException("Expecting '\"' at position " + positionInStream() + ". Found " + (char) nextToken);
			}
			if (nextToken != ':') {
				throw new IOException("Expecting ':' at position " + positionInStream() + ". Found " + (char) nextToken);
			}
			getNextToken();
			nextToken = skip();
			while (nextToken == ',') {
				nextToken = getNextToken();
				if (nextToken == '"') {
					nextToken = skipString();
				} else {
					throw new IOException("Expecting '\"' at position " + positionInStream() + ". Found " + (char) nextToken);
				}
				if (nextToken != ':') {
					throw new IOException("Expecting ':' at position " + positionInStream() + ". Found " + (char) nextToken);
				}
				getNextToken();
				nextToken = skip();
			}
			if (nextToken != '}') {
				throw new IOException("Expecting '}' at position " + positionInStream() + ". Found " + (char) nextToken);
			}
			return getNextToken();
		}
		if (last == '[') {
			getNextToken();
			byte nextToken = skip();
			while (nextToken == ',') {
				getNextToken();
				nextToken = skip();
			}
			if (nextToken != ']') {
				throw new IOException("Expecting ']' at position " + positionInStream() + ". Found " + (char) nextToken);
			}
			return getNextToken();
		}
		if (last == 'n') {
			if (!wasNull()) {
				throw new IOException("Expecting 'null' at position " + positionInStream());
			}
			return getNextToken();
		}
		if (last == 't') {
			if (!wasTrue()) {
				throw new IOException("Expecting 'true' at position " + positionInStream());
			}
			return getNextToken();
		}
		if (last == 'f') {
			if (!wasFalse()) {
				throw new IOException("Expecting 'false' at position " + positionInStream());
			}
			return getNextToken();
		}
		while (last != ',' && last != '}' && last != ']') {
			read();
		}
		return last;
	}

	/**
	 * will be removed
	 *
	 * @return not used anymore
	 * @throws IOException throws if invalid JSON detected
	 */
	@Deprecated
	public String readNext() throws IOException {
		final int start = currentIndex - 1;
		skip();
		return new String(buffer, start, currentIndex - start - 1, "UTF-8");
	}

	public final byte[] readBase64() throws IOException {
		if (stream != null && Base64.findEnd(buffer, currentIndex) == buffer.length) {
			final int len = parseString();
			return DatatypeConverter.parseBase64Binary(new String(chars, 0, len));
		}
		if (last != '"') {
			throw new IOException("Expecting '\"' at position " + positionInStream() + " at base64 start. Found " + (char) last);
		}
		final int start = currentIndex;
		currentIndex = Base64.findEnd(buffer, start);
		last = buffer[currentIndex++];
		if (last != '"') {
			throw new IOException("Expecting '\"' at position " + positionInStream() + " at base64 end. Found " + (char) last);
		}
		return Base64.decodeFast(buffer, start, currentIndex - 1);
	}

	/**
	 * Read key value of JSON input.
	 * If key cache is used, it will be looked up from there.
	 *
	 * @return parsed key value
	 * @throws IOException unable to parse string input
	 */
	public final String readKey() throws IOException {
		final int len = parseString();
		final String key = keyCache != null ? keyCache.get(chars, len) : new String(chars, 0, len);
		if (getNextToken() != ':') {
			throw new IOException("Expecting ':' at position " + positionInStream() + ". Found " + (char) last);
		}
		getNextToken();
		return key;
	}

	/**
	 * Custom objects can be deserialized based on the implementation specified through this interface.
	 * Annotation processor creates custom deserializers at compile time and registers them into DslJson.
	 *
	 * @param <T> type
	 */
	public interface ReadObject<T> {
		T read(JsonReader reader) throws IOException;
	}

	/**
	 * Existing instances can be provided as target for deserialization.
	 * Annotation processor creates custom deserializers at compile time and registers them into DslJson.
	 *
	 * @param <T> type
	 */
	public interface BindObject<T> {
		T bind(JsonReader reader, T instance) throws IOException;
	}

	public interface ReadJsonObject<T extends JsonObject> {
		T deserialize(JsonReader reader) throws IOException;
	}

	/**
	 * Checks if 'null' value is at current position.
	 * This means last read byte was 'n' and 'ull' are next three bytes.
	 * If last byte was n but next three are not 'ull' it will throw since that is not a valid JSON construct.
	 *
	 * @return true if 'null' value is at current position
	 * @throws IOException invalid 'null' value detected
	 */
	public final boolean wasNull() throws IOException {
		if (last == 'n') {
			if (currentIndex + 2 < length && buffer[currentIndex] == 'u'
					&& buffer[currentIndex + 1] == 'l' && buffer[currentIndex + 2] == 'l') {
				currentIndex += 3;
				last = 'l';
				return true;
			}
			throw new IOException("Invalid null value found at: " + positionInStream());
		}
		return false;
	}

	/**
	 * Checks if 'true' value is at current position.
	 * This means last read byte was 't' and 'rue' are next three bytes.
	 * If last byte was t but next three are not 'rue' it will throw since that is not a valid JSON construct.
	 *
	 * @return true if 'true' value is at current position
	 * @throws IOException invalid 'true' value detected
	 */
	public final boolean wasTrue() throws IOException {
		if (last == 't') {
			if (currentIndex + 2 < length && buffer[currentIndex] == 'r'
					&& buffer[currentIndex + 1] == 'u' && buffer[currentIndex + 2] == 'e') {
				currentIndex += 3;
				last = 'e';
				return true;
			}
			throw new IOException("Invalid boolean value found at: " + positionInStream());
		}
		return false;
	}

	/**
	 * Checks if 'false' value is at current position.
	 * This means last read byte was 'f' and 'alse' are next four bytes.
	 * If last byte was f but next four are not 'alse' it will throw since that is not a valid JSON construct.
	 *
	 * @return true if 'false' value is at current position
	 * @throws IOException invalid 'false' value detected
	 */
	public final boolean wasFalse() throws IOException {
		if (last == 'f') {
			if (currentIndex + 3 < length && buffer[currentIndex] == 'a'
					&& buffer[currentIndex + 1] == 'l' && buffer[currentIndex + 2] == 's'
					&& buffer[currentIndex + 3] == 'e') {
				currentIndex += 4;
				last = 'e';
				return true;
			}
			throw new IOException("Invalid boolean value found at: " + positionInStream());
		}
		return false;
	}

	/**
	 * Will advance to next token and check if it's comma
	 *
	 * @throws IOException it's not comma
	 */
	public final void comma() throws IOException {
		if (getNextToken() != ',') {
			if (currentIndex >= length) throw new IOException("Unexpected end in JSON at: " + positionInStream());
			else throw new IOException("Expecting ',' at position " + positionInStream() + ". Found " + (char) last);
		}
	}

	/**
	 * Will advance to next token and check if it's semicolon
	 *
	 * @throws IOException it's not semicolon
	 */
	public final void semicolon() throws IOException {
		if (getNextToken() != ':') {
			if (currentIndex >= length) throw new IOException("Unexpected end in JSON at: " + positionInStream());
			else throw new IOException("Expecting ':' at position " + positionInStream() + ". Found " + (char) last);
		}
	}

	/**
	 * Will advance to next token and check if it's array start
	 *
	 * @throws IOException it's not array start
	 */
	public final void startArray() throws IOException {
		if (getNextToken() != '[') {
			if (currentIndex >= length) throw new IOException("Unexpected start of collection in JSON at: " + positionInStream());
			else throw new IOException("Expecting '[' at position " + positionInStream() + ". Found " + (char) last);
		}
	}

	/**
	 * Will advance to next token and check if it's array end
	 *
	 * @throws IOException it's not array end
	 */
	public final void endArray() throws IOException {
		if (getNextToken() != ']') {
			if (currentIndex >= length) throw new IOException("Unexpected end of collection in JSON at: " + positionInStream());
			else throw new IOException("Expecting ']' at position " + positionInStream() + ". Found " + (char) last);
		}
	}

	/**
	 * Will advance to next token and check if it's object start
	 *
	 * @throws IOException it's not object start
	 */
	public final void startObject() throws IOException {
		if (getNextToken() != '{') {
			if (currentIndex >= length) throw new IOException("Unexpected start of object in JSON at: " + positionInStream());
			else throw new IOException("Expecting '{' at position " + positionInStream() + ". Found " + (char) last);
		}
	}

	/**
	 * Will advance to next token and check it it's object end
	 *
	 * @throws IOException it's not object end
	 */
	public final void endObject() throws IOException {
		if (getNextToken() != '}') {
			if (currentIndex >= length) throw new IOException("Unexpected end of object in JSON at: " + positionInStream());
			else throw new IOException("Expecting '}' at position " + positionInStream() + ". Found " + (char) last);
		}
	}

	/**
	 * Check if the last read token is an array end
	 *
	 * @throws IOException it's not array end
	 */
	public final void checkArrayEnd() throws IOException {
		if (last != ']') {
			if (currentIndex >= length) throw new IOException("Unexpected end of JSON in collection at: " + positionInStream());
			else throw new IOException("Expecting ']' at position " + positionInStream() + ". Found " + (char) last);
		}
	}

	private Object readNull(Class<?> manifest) throws IOException {
		if (!wasNull()) throw new IllegalArgumentException("Invalid JSON detected at: " + positionInStream());
		if (manifest.isPrimitive()) {
			if (manifest == int.class) return 0;
			else if (manifest == long.class) return 0L;
			else if (manifest == short.class) return (short)0;
			else if (manifest == byte.class) return (byte)0;
			else if (manifest == float.class) return 0f;
			else if (manifest == double.class) return 0d;
			else if (manifest == boolean.class) return false;
			else if (manifest == char.class) return '\0';
		}
		return null;
	}

	/**
	 * Will advance to next token and read the JSON into specified type
	 *
	 * @param manifest type to read into
	 * @param <T> type
	 * @return new instance from input JSON
	 * @throws IOException unable to process JSON
	 */
	@SuppressWarnings("unchecked")
	public final <T> T next(Class<T> manifest) throws IOException {
		if (manifest == null) throw new IllegalArgumentException("manifest can't be null");
		if (this.getNextToken() == 'n') {
			return (T)readNull(manifest);
		}
		final ReadObject<T> reader = (ReadObject<T>) readers.get(manifest);
		if (reader == null) {
			throw new IllegalArgumentException("Reader not found for " + manifest + ". Check if reader was registered");
		}
		return reader.read(this);
	}

	/**
	 * Will advance to next token and bind the JSON to provided instance
	 *
	 * @param manifest type to read into
	 * @param instance instance to bind
	 * @param <T> type
	 * @return bound instance
	 * @throws IOException unable to process JSON
	 */
	@SuppressWarnings("unchecked")
	public final <T> T next(Class<T> manifest, T instance) throws IOException {
		if (manifest == null) throw new IllegalArgumentException("manifest can't be null");
		if (this.getNextToken() == 'n') {
			return (T)readNull(manifest);
		}
		if (instance == null) throw new IllegalArgumentException("instance can't be null");
		final BindObject<T> binder = (BindObject<T>)binders.get(manifest);
		if (binder == null) throw new IllegalArgumentException("Binder not found for " + manifest + ". Check if binder was registered");
		return binder.bind(this, instance);
	}

	public final <T, S extends T> ArrayList<T> deserializeCollection(final ReadObject<S> readObject) throws IOException {
		final ArrayList<T> res = new ArrayList<T>(4);
		deserializeCollection(readObject, res);
		return res;
	}

	public final <T, S extends T> void deserializeCollection(final ReadObject<S> readObject, final Collection<T> res) throws IOException {
		res.add(readObject.read(this));
		while (getNextToken() == ',') {
			getNextToken();
			res.add(readObject.read(this));
		}
		checkArrayEnd();
	}

	public final <T, S extends T> ArrayList<T> deserializeNullableCollection(final ReadObject<S> readObject) throws IOException {
		final ArrayList<T> res = new ArrayList<T>(4);
		deserializeNullableCollection(readObject, res);
		return res;
	}

	public final <T, S extends T> void deserializeNullableCollection(final ReadObject<S> readObject, final Collection<T> res) throws IOException {
		if (wasNull()) {
			res.add(null);
		} else {
			res.add(readObject.read(this));
		}
		while (getNextToken() == ',') {
			getNextToken();
			if (wasNull()) {
				res.add(null);
			} else {
				res.add(readObject.read(this));
			}
		}
		checkArrayEnd();
	}

	public final <T extends JsonObject> ArrayList<T> deserializeCollection(final ReadJsonObject<T> readObject) throws IOException {
		final ArrayList<T> res = new ArrayList<T>(4);
		deserializeCollection(readObject, res);
		return res;
	}

	public final <T extends JsonObject> void deserializeCollection(final ReadJsonObject<T> readObject, final Collection<T> res) throws IOException {
		if (last == '{') {
			getNextToken();
			res.add(readObject.deserialize(this));
		} else throw new IOException("Expecting '{' at position " + positionInStream() + ". Found " + (char) last);
		while (getNextToken() == ',') {
			if (getNextToken() == '{') {
				getNextToken();
				res.add(readObject.deserialize(this));
			} else throw new IOException("Expecting '{' at position " + positionInStream() + ". Found " + (char) last);
		}
		checkArrayEnd();
	}

	public final <T extends JsonObject> ArrayList<T> deserializeNullableCollection(final ReadJsonObject<T> readObject) throws IOException {
		final ArrayList<T> res = new ArrayList<T>(4);
		deserializeNullableCollection(readObject, res);
		return res;
	}

	public final <T extends JsonObject> void deserializeNullableCollection(final ReadJsonObject<T> readObject, final Collection<T> res) throws IOException {
		if (last == '{') {
			getNextToken();
			res.add(readObject.deserialize(this));
		} else if (wasNull()) {
			res.add(null);
		} else throw new IOException("Expecting '{' at position " + positionInStream() + ". Found " + (char) last);
		while (getNextToken() == ',') {
			if (getNextToken() == '{') {
				getNextToken();
				res.add(readObject.deserialize(this));
			} else if (wasNull()) {
				res.add(null);
			} else throw new IOException("Expecting '{' at position " + positionInStream() + ". Found " + (char) last);
		}
		checkArrayEnd();
	}

	public final <T> Iterator<T> iterateOver(final JsonReader.ReadObject<T> reader) throws IOException {
		return new WithReader<T>(reader, this);
	}

	public final <T extends JsonObject> Iterator<T> iterateOver(final JsonReader.ReadJsonObject<T> reader) throws IOException {
		return new WithObjectReader<T>(reader, this);
	}

	private static class WithReader<T> implements Iterator<T> {
		private final JsonReader.ReadObject<T> reader;
		private final JsonReader json;

		private boolean hasNext;

		WithReader(JsonReader.ReadObject<T> reader, JsonReader json) {
			this.reader = reader;
			this.json = json;
			hasNext = true;
		}

		@Override
		public boolean hasNext() {
			return hasNext;
		}

		@Override
		public void remove() {
		}

		@Override
		public T next() {
			try {
				byte nextToken = json.last();
				final T instance;
				if (nextToken == 'n') {
					if (json.wasNull()) {
						instance = null;
					} else {
						throw json.expecting("null");
					}
				} else {
					instance = reader.read(json);
				}
				hasNext = json.getNextToken() == ',';
				if (hasNext) {
					json.getNextToken();
				} else {
					if (json.last() != ']') {
						throw json.expecting("]");
					}
					//TODO: ideally we should release stream bound to reader
				}
				return instance;
			} catch (IOException e) {
				throw new SerializationException(e);
			}
		}
	}

	private static class WithObjectReader<T extends JsonObject> implements Iterator<T> {
		private final JsonReader.ReadJsonObject<T> reader;
		private final JsonReader json;

		private boolean hasNext;

		WithObjectReader(JsonReader.ReadJsonObject<T> reader, JsonReader json) {
			this.reader = reader;
			this.json = json;
			hasNext = true;
		}

		@Override
		public boolean hasNext() {
			return hasNext;
		}

		@Override
		public void remove() {
		}

		@Override
		public T next() {
			try {
				byte nextToken = json.last();
				final T instance;
				if (nextToken == 'n') {
					if (json.wasNull()) {
						instance = null;
					} else {
						throw json.expecting("null");
					}
				} else if (nextToken == '{') {
					json.getNextToken();
					instance = reader.deserialize(json);
				} else {
					throw json.expecting("{");
				}
				hasNext = json.getNextToken() == ',';
				if (hasNext) {
					json.getNextToken();
				} else {
					if (json.last() != ']') {
						throw json.expecting("]");
					}
					//TODO: ideally we should release stream bound to reader
				}
				return instance;
			} catch (IOException e) {
				throw new SerializationException(e);
			}
		}
	}
}
