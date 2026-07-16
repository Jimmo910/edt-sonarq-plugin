/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Properties;

import org.junit.Test;

/**
 * Verifies that the NLS message bundles are complete and bound.
 */
public class LocalizationResourcesTest
{
    @Test
    public void russianBundleCoversAllKeys() throws IOException
    {
        Properties en = load("messages.properties");
        Properties ru = load("messages_ru.properties");
        assertEquals(en.stringPropertyNames(), ru.stringPropertyNames());
        assertFalse(en.stringPropertyNames().isEmpty());
    }

    @Test
    public void allMessageFieldsAreBound() throws IllegalAccessException
    {
        for (Field field : Messages.class.getFields())
        {
            if (field.getType() == String.class)
            {
                String value = (String)field.get(null);
                assertNotNull(field.getName(), value);
                assertFalse(field.getName(), value.startsWith("NLS missing message"));
            }
        }
    }

    private static Properties load(String name) throws IOException
    {
        try (InputStream in = Messages.class.getResourceAsStream(name))
        {
            assertNotNull(name, in);
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        }
    }
}
