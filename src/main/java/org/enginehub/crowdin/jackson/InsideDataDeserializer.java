/*
 * This file is part of crowdin-distributor, licensed under GPLv3.
 *
 * Copyright (c) EngineHub <https://enginehub.org/>
 * Copyright (c) contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.enginehub.crowdin.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.ResolvableDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

public class InsideDataDeserializer<T> extends StdDeserializer<T> implements ContextualDeserializer, ResolvableDeserializer {

    private final JsonDeserializer<T> originalDeserializer;

    protected InsideDataDeserializer(JavaType valueType, JsonDeserializer<T> originalDeserializer) {
        super(valueType);
        this.originalDeserializer = originalDeserializer;
    }

    protected InsideDataDeserializer(InsideDataDeserializer<?> old,
                                     JsonDeserializer<T> originalDeserializer) {
        super(old);
        this.originalDeserializer = originalDeserializer;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) throws JsonMappingException {
        if (originalDeserializer instanceof ContextualDeserializer contextualDeserializer) {
            return new InsideDataDeserializer<>(
                this,
                contextualDeserializer.createContextual(ctxt, property)
            );
        }
        return this;
    }

    @Override
    public void resolve(DeserializationContext ctxt) throws JsonMappingException {
        if (originalDeserializer instanceof ResolvableDeserializer resolvableDeserializer) {
            resolvableDeserializer.resolve(ctxt);
        }
    }

    @Override
    public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == JsonToken.START_OBJECT) {
            p.nextToken();
        }
        String name = p.currentName();
        while (!"data".equals(name)) {
            p.nextToken();
            p.skipChildren();
            if ((name = p.nextFieldName()) == null) {
                ctxt.reportWrongTokenException(
                    _valueType, JsonToken.FIELD_NAME,
                    "Didn't find \"data\" field"
                );
            }
        }
        p.nextToken();

        T value = originalDeserializer.deserialize(p, ctxt);

        // Clear out the rest of the object

        while (p.nextFieldName() != null) {
            p.nextToken();
            p.skipChildren();
        }

        return value;
    }
}
