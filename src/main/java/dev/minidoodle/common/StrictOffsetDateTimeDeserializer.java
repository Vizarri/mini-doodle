package dev.minidoodle.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Enforces the API timestamp contract instead of Jackson's epoch-number coercion. */
public final class StrictOffsetDateTimeDeserializer extends StdDeserializer<OffsetDateTime> {
    public StrictOffsetDateTimeDeserializer() {
        super(OffsetDateTime.class);
    }

    @Override
    public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            throw invalidTimestamp(parser);
        }
        try {
            return OffsetDateTime.parse(parser.getText(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException exception) {
            throw invalidTimestamp(parser);
        }
    }

    private static InvalidFormatException invalidTimestamp(JsonParser parser) throws IOException {
        return InvalidFormatException.from(parser,
                "Timestamp must be an ISO 8601 string with an explicit UTC offset",
                parser.getText(), OffsetDateTime.class);
    }
}
