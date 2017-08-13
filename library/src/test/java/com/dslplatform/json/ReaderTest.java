package com.dslplatform.json;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

public class ReaderTest {

	private final DslJson<Object> dslJson = new DslJson<Object>();

	@Test
	public void testLastName() throws IOException {
		final byte[] buf = "\"number\":1234".getBytes("UTF-8");
		testLastName(dslJson.newReader(buf));
		testLastName(dslJson.newReader(new ByteArrayInputStream(buf), new byte[64]));
	}

	private void testLastName(JsonReader<Object> jr) throws IOException {
		jr.getNextToken();
		jr.fillName();
		Assert.assertEquals("number", jr.getLastName());
		jr.getNextToken();
		Assert.assertTrue(jr.wasLastName("number"));
		int num = NumberConverter.deserializeInt(jr);
		Assert.assertEquals(1234, num);
	}

	@Test
	public void testCalcHashNameEndSameAsFillName() throws IOException {
		final byte[] buf = "\"number\":1234".getBytes("UTF-8");
		testCalcHashNameEndSameAsFillName(dslJson.newReader(buf));
		testCalcHashNameEndSameAsFillName(dslJson.newReader(new ByteArrayInputStream(buf), new byte[64]));
	}

	private void testCalcHashNameEndSameAsFillName(JsonReader<Object> jr) throws IOException {
		jr.getNextToken();
		jr.calcHash();
		Assert.assertTrue(jr.wasLastName("number"));
		Assert.assertEquals("number", jr.getLastName());
	}

	static class Implementation implements Interface {
	}

	interface Interface {
	}

	@Test
	public void testReaderOnInterface() throws IOException {
		DslJson<Object> dslJson = new DslJson<Object>();
		dslJson.registerReader(Implementation.class, new JsonReader.ReadObject<Implementation>() {
			@Override
			public Implementation read(JsonReader reader) throws IOException {
				return null;
			}
		});
		JsonReader.ReadObject<?> reader1 = dslJson.tryFindReader(Interface.class);
		Assert.assertNull(reader1);
		JsonReader.ReadObject<?> reader2 = dslJson.tryFindReader(Implementation.class);
		Assert.assertNotNull(reader2);
		dslJson.registerReader(Interface.class, dslJson.tryFindReader(Implementation.class));
		JsonReader.ReadObject<?> reader3 = dslJson.tryFindReader(Interface.class);
		Assert.assertNotNull(reader3);
	}

	@Test
	public void skipEscaped1() throws IOException {
		DslJson<Object> dslJson = new DslJson<Object>();
		byte[] input = "{\"a\":1,\"b\":\"\\\",\"c\":\"\\\\\"}".getBytes("UTF-8");
		JsonReader reader = dslJson.newReader(input);
		Assert.assertEquals('{', reader.getNextToken());
		Assert.assertEquals('"', reader.getNextToken());
		Assert.assertEquals("a", reader.readKey());
		Assert.assertEquals(',', reader.skip());
		Assert.assertEquals('"', reader.getNextToken());
		Assert.assertEquals("b", reader.readKey());
		Assert.assertEquals('c', reader.skip());
	}

	@Test
	public void skipEscaped2() throws IOException {
		DslJson<Object> dslJson = new DslJson<Object>();
		byte[] input = "{\"a\":1,\"b\":\"\\\"\",\"c\":\"\\\\\"}".getBytes("UTF-8");
		JsonReader reader = dslJson.newReader(input);
		Assert.assertEquals('{', reader.getNextToken());
		Assert.assertEquals('"', reader.getNextToken());
		Assert.assertEquals("a", reader.readKey());
		Assert.assertEquals(',', reader.skip());
		Assert.assertEquals('"', reader.getNextToken());
		Assert.assertEquals("b", reader.readKey());
		Assert.assertEquals(',', reader.skip());
	}

	@Test
	public void skipEscaped3() throws IOException {
		DslJson<Object> dslJson = new DslJson<Object>();
		byte[] input = "{\"a\":1,\"b\":\"\\\\\",\"c\":\"\\\\\\\"\",\"d\":\"\\\"abc\"}".getBytes("UTF-8");
		Map<String, Object> map = dslJson.deserialize(Map.class, input, input.length);
		Assert.assertEquals(4, map.size());
		Assert.assertEquals(map.get("a"), 1L);
		Assert.assertEquals(map.get("b"), "\\");
		Assert.assertEquals(map.get("c"), "\\\"");
		JsonReader reader = dslJson.newReader(input);
		Assert.assertEquals('{', reader.getNextToken());
		Assert.assertEquals('"', reader.getNextToken());
		Assert.assertEquals("a", reader.readKey());
		Assert.assertEquals(',', reader.skip());
		Assert.assertEquals('"', reader.getNextToken());
		Assert.assertEquals("b", reader.readKey());
		Assert.assertEquals(',', reader.skip());
		Assert.assertEquals('"', reader.getNextToken());
		Assert.assertEquals("c", reader.readKey());
		Assert.assertEquals(',', reader.skip());
		Assert.assertEquals('"', reader.getNextToken());
		Assert.assertEquals("d", reader.readKey());
		Assert.assertEquals('}', reader.skip());
	}

	@Test
	public void canReadStringAtTheEndOfLongBuffer() throws IOException, InterruptedException {
		StringBuilder sb = new StringBuilder();
		sb.append("\"");
		for (int i = 0; i < 10; i++) {
			sb.append("abcdefghijklmnopq");
		}
		sb.append("\"");
		String largeString = sb.toString();
		byte[] bytes = largeString.getBytes();
		DslJson<Object> json = new DslJson<Object>();
		try {
			json.deserialize(String.class, bytes, bytes.length - 1);
			Assert.fail();
		} catch (IOException e) {
			Assert.assertTrue(e.getMessage().contains("at: 171"));
		}
		try {
			json.deserialize(String.class, new ByteArrayInputStream(bytes, 0, bytes.length - 1), new byte[64]);
			Assert.fail();
		} catch (IOException e) {
			Assert.assertTrue(e.getMessage().contains("at: 171"));
		}
	}

	@Test
	public void canReadStringAtTheEndOfShortBuffer() throws IOException, InterruptedException {
		String largeString = "\"abcdefghijklmnopq\"";
		byte[] bytes = largeString.getBytes();
		DslJson<Object> json = new DslJson<Object>();
		try {
			json.deserialize(String.class, bytes, bytes.length - 1);
			Assert.fail();
		} catch (IOException e) {
			Assert.assertTrue(e.getMessage().contains("at: 18"));
		}
		try {
			json.deserialize(String.class, new ByteArrayInputStream(bytes, 0, bytes.length - 1), new byte[64]);
			Assert.fail();
		} catch (IOException e) {
			Assert.assertTrue(e.getMessage().contains("at: 18"));
		}
	}

	@Test
	public void readerFromLookup() throws IOException, InterruptedException {
		String input = "\"abcdefghijklmnopq\"";
		byte[] bytes = input.getBytes();
		DslJson<Object> json = new DslJson<Object>();
		JsonReader<Object> reader = json.newReader().process(bytes, bytes.length);
		String output = reader.next(String.class);
		Assert.assertEquals("abcdefghijklmnopq", output);
	}

	@Test
	public void readerMultipleFromLookup() throws IOException, InterruptedException {
		String input = "[\"abc\",123,null,456]";
		byte[] bytes = input.getBytes();
		DslJson<Object> json = new DslJson<Object>();
		JsonReader<Object> reader = json.newReader().process(bytes, bytes.length);
		reader.startArray();
		String str1 = reader.next(String.class);
		reader.comma();
		int num2 = reader.next(int.class);
		reader.comma();
		String str3 = reader.next(String.class);
		reader.comma();
		long num4 = reader.next(long.class);
		reader.endArray();
		Assert.assertEquals("abc", str1);
		Assert.assertEquals(123, num2);
		Assert.assertNull(str3);
		Assert.assertEquals(456L, num4);
	}

	@Test
	public void readerNullIntoInt() throws IOException, InterruptedException {
		String input = " null";
		byte[] bytes = input.getBytes();
		DslJson<Object> json = new DslJson<Object>();
		JsonReader<Object> reader = json.newReader().process(bytes, bytes.length);
		int num = reader.next(int.class);
		Assert.assertEquals(0, num);
	}

	class MyBind {
		public int i;
		public String s;
	}

	@Test
	public void bindObject() throws IOException, InterruptedException {
		String input = "{\"i\":12,\"s\":\"abc\"}";
		byte[] bytes = input.getBytes();
		DslJson<Object> json = new DslJson<Object>();
		json.registerBinder(MyBind.class, new JsonReader.BindObject<MyBind>() {
			@Override
			public MyBind bind(JsonReader reader, MyBind instance) throws IOException {
				JsonReader<Object> typedReader = reader;
				Assert.assertEquals('{', reader.last());
				Assert.assertEquals("i", reader.next(String.class));
				reader.semicolon();
				instance.i = typedReader.next(int.class);
				reader.comma();
				Assert.assertEquals("s", reader.next(String.class));
				reader.semicolon();
				instance.s = typedReader.next(String.class);
				reader.endObject();
				return instance;
			}
		});
		JsonReader<Object> reader = json.newReader().process(bytes, bytes.length);
		MyBind instance = new MyBind();
		MyBind bound = reader.next(MyBind.class, instance);
		Assert.assertEquals(12, bound.i);
		Assert.assertEquals("abc", bound.s);
		Assert.assertSame(instance, bound);
	}
}
