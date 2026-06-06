package com.datasync.core.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MySqlToDm8TypeMapperTest {

    private MySqlToDm8TypeMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new MySqlToDm8TypeMapper();
    }

    @Test
    void testMapTypeName_Varchar() {
        assertEquals("VARCHAR", mapper.mapTypeName("VARCHAR"));
    }

    @Test
    void testMapTypeName_TinyInt() {
        assertEquals("SMALLINT", mapper.mapTypeName("TINYINT"));
    }

    @Test
    void testMapTypeName_Year() {
        assertEquals("INT", mapper.mapTypeName("YEAR"));
    }

    @Test
    void testMapTypeName_Json() {
        assertEquals("CLOB", mapper.mapTypeName("JSON"));
    }

    @Test
    void testMapTypeName_Unknown() {
        assertEquals("VARCHAR", mapper.mapTypeName("UNKNOWN_TYPE"));
    }

    @Test
    void testMapValue_NullInput() {
        assertNull(mapper.mapType("VARCHAR", null));
    }

    @Test
    void testMapValue_TinyIntToSmallInt() {
        Object result = mapper.mapType("TINYINT", (byte) 1);
        assertEquals(Short.valueOf((short) 1), result);
    }
}