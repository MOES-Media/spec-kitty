package ai.speckitty.producer.adapter.json;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.order.CodePoints;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.SerializableString;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.io.CharacterEscapes;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonWriteFeature;

/**
 * The one JSON reader and writer of the producer (Jackson streaming, configured once). Values are the domain model: {@code null},
 * {@link Boolean}, {@link String}, {@link JsonNumber} (the number's own text, so {@code 5.0} is not {@code 5}), {@link List} and
 * {@link Map}. The reader refuses duplicate members, trailing content, a BOM and a lone surrogate. The writer is deterministic:
 * members sorted by code point, compact, ASCII only (every other character escaped), number text verbatim, no BOM, no LF.
 */
public final class Json {

    /** The tilde: every character above it, DEL included, is escaped, as Python's {@code ensure_ascii} does. */
    private static final int LAST_PRINTABLE = 0x7e;

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .highestNonEscapedChar(LAST_PRINTABLE)
            .characterEscapes(new DelEscape())
            .disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
            .build();

    /** Adds DEL (0x7f) to the characters Jackson escapes by default, so the output holds no control character at all. */
    private static final class DelEscape extends CharacterEscapes {
        private static final long serialVersionUID = 1L;
        private static final int DEL = 0x7f;

        @Override
        public int[] getEscapeCodesForAscii() {
            int[] codes = CharacterEscapes.standardAsciiEscapesForJSON();
            codes[DEL] = CharacterEscapes.ESCAPE_STANDARD;
            return codes;
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            return null;
        }
    }

    private Json() {}

    /** Parses one JSON object from UTF-8 bytes; invalid UTF-8 and a BOM are refused. */
    public static ParseResult<Map<String, Object>> parseObject(byte[] utf8) {
        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8))
                    .toString();
            return parseObject(text);
        } catch (CharacterCodingException e) {
            return refused("not valid UTF-8");
        }
    }

    /** Parses one JSON object; anything else, or anything malformed, is refused as {@code rejected}. */
    public static ParseResult<Map<String, Object>> parseObject(String text) {
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            return refused("a byte order mark");
        }
        try (JsonParser parser = FACTORY.createParser(ObjectReadContext.empty(), text)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return refused("not a JSON object");
            }
            Map<String, Object> object = readObject(parser);
            if (parser.nextToken() != null) {
                return refused("content after the object");
            }
            return new ParseResult.Accepted<>(object);
        } catch (JacksonException | IllegalArgumentException e) {
            return refused("malformed JSON");
        }
    }

    private static ParseResult<Map<String, Object>> refused(String why) {
        return new ParseResult.Refused<>(FaultReason.REJECTED, why);
    }

    private static Object readValue(JsonParser parser, JsonToken token) {
        return switch (token) {
            case START_OBJECT -> readObject(parser);
            case START_ARRAY -> readArray(parser);
            case VALUE_STRING -> checked(parser.getString());
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> new JsonNumber(parser.getString());
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new IllegalArgumentException("unexpected token " + token);
        };
    }

    private static Map<String, Object> readObject(JsonParser parser) {
        Map<String, Object> object = new LinkedHashMap<>();
        for (JsonToken token = parser.nextToken(); token != JsonToken.END_OBJECT; token = parser.nextToken()) {
            if (token != JsonToken.PROPERTY_NAME) {
                throw new IllegalArgumentException("expected a member name");
            }
            String name = checked(parser.currentName());
            JsonToken valueToken = parser.nextToken();
            if (valueToken == null) {
                throw new IllegalArgumentException("truncated");
            }
            object.put(name, readValue(parser, valueToken));
        }
        return object;
    }

    private static List<Object> readArray(JsonParser parser) {
        List<Object> list = new ArrayList<>();
        for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
            if (token == null) {
                throw new IllegalArgumentException("truncated");
            }
            list.add(readValue(parser, token));
        }
        return list;
    }

    /** A string with a lone surrogate has no UTF-8 form, so it is refused rather than written as a replacement character. */
    private static String checked(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new IllegalArgumentException("lone surrogate");
            }
        }
        return text;
    }

    /** The deterministic text of a value of the domain model; a value outside the model is an {@link IllegalArgumentException}. */
    public static String render(Object value) {
        StringWriter out = new StringWriter();
        try (JsonGenerator generator = FACTORY.createGenerator(ObjectWriteContext.empty(), out)) {
            write(generator, value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("not renderable", e);
        }
        return out.toString();
    }

    private static void write(JsonGenerator generator, Object value) {
        if (value == null) {
            generator.writeNull();
        } else if (value instanceof Boolean flag) {
            generator.writeBoolean(flag);
        } else if (value instanceof String text) {
            generator.writeString(checked(text));
        } else if (value instanceof JsonNumber number) {
            generator.writeNumber(number.literal());
        } else if (value instanceof List<?> list) {
            generator.writeStartArray();
            for (Object element : list) {
                write(generator, element);
            }
            generator.writeEndArray();
        } else if (value instanceof Map<?, ?> map) {
            List<String> names = new ArrayList<>();
            for (Object key : map.keySet()) {
                if (!(key instanceof String name)) {
                    throw new IllegalArgumentException("a JSON object has String keys");
                }
                names.add(checked(name));
            }
            names.sort(CodePoints::compare);
            generator.writeStartObject();
            for (String name : names) {
                generator.writeName(name);
                write(generator, map.get(name));
            }
            generator.writeEndObject();
        } else {
            throw new IllegalArgumentException("not a JSON model value: " + value.getClass().getName());
        }
    }
}
