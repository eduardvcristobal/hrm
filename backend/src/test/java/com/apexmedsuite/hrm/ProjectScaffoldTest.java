package com.apexmedsuite.hrm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectScaffoldTest {
    @Test void java17RecordWorks() {
        record Version(int major, int minor) {}
        assertEquals(17, Runtime.version().feature() >= 17 ? 17 : -1);
        assertEquals(1, new Version(1, 0).major());
    }
}
