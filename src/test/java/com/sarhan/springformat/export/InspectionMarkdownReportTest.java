/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sarhan.springformat.export;

import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link InspectionMarkdownReport}.
 *
 * @author Akmal Sarhan
 */
public class InspectionMarkdownReportTest {

    @Test
    public void groupsProblemsByInspectionWithDescriptionAndSortedLocations() {
        InspectionMarkdownReport.InspectionSection pitfalls = new InspectionMarkdownReport.InspectionSection(
                "SpringAsyncWithoutExecutor", "Spring Async without Executor", "ERROR", "Spring Buddy > 4. pitfalls",
                "Without a custom executor Spring falls back to SimpleAsyncTaskExecutor.",
                List.of(
                        new InspectionMarkdownReport.Problem("src/main/java/Bar.java", 17,
                                "@Async method uses the default executor"),
                        new InspectionMarkdownReport.Problem("src/main/java/Foo.java", 42,
                                "@Async method uses the default executor"),
                        new InspectionMarkdownReport.Problem("src/main/java/Foo.java", 3,
                                "duplicate entry sorts first")));
        InspectionMarkdownReport.InspectionSection migration = new InspectionMarkdownReport.InspectionSection(
                "SpringBoot3JavaxImport", "Javax import should be migrated", "WARNING",
                "Spring Buddy > 3. boot 2 -> 3 migration",
                "Use jakarta imports.", List.of(
                new InspectionMarkdownReport.Problem("src/test/java/Util.java", 5, "javax import")));

        String report = InspectionMarkdownReport.build("Spring Buddy", "/Users/dev/project", "2026-10-23 18:00",
                List.of(pitfalls, migration));

        assertTrue(report.startsWith("# Spring Buddy Inspection Report"));
        assertTrue(report.contains("Machine-readable export of Spring Buddy inspection results"));
        assertTrue(report.contains("Verify a finding by opening the listed file at the listed 1-based line number."));
        assertTrue(report.contains("Every row in an Occurrences table is a task with a Done checkbox ('- [ ]')."));
        assertTrue(report.contains("Verify the finding first, fix it second:"));
        assertTrue(report.contains("Mark a task finished by changing its Done cell from '- [ ]' to '- [x]'."));
        assertTrue(report.contains("Example prompt for an AI agent:"));
        assertTrue(report.contains("work through every task whose Done cell is still '- [ ]'"));
        assertTrue(report.contains("first verify that the finding actually holds"));
        assertTrue(report.contains("if it does not hold, leave the box unchecked and"));
        assertTrue(report.contains("state the reason"));
        assertTrue(report.contains("flip that row's Done cell from '- [ ]' to '- [x]'"));
        assertTrue(report.contains("leave the box unchecked and state the reason in the Message cell"));
        assertTrue(report.contains("- Generated: 2026-10-23 18:00"));
        assertTrue(report.contains("- Project root: /Users/dev/project"));
        assertTrue(report.contains("- Scope: Spring Buddy"));
        assertTrue(report.contains("- Inspections: 2"));
        assertTrue(report.contains("- Problems: 4"));
        assertTrue(report.contains("- Files affected: 3"));

        int pitfallsIndex = report.indexOf("## SpringAsyncWithoutExecutor — Spring Async without Executor [ERROR]");
        int migrationIndex = report.indexOf("## SpringBoot3JavaxImport — Javax import should be migrated [WARNING]");
        assertTrue(pitfallsIndex >= 0);
        assertTrue(migrationIndex > pitfallsIndex);

        assertTrue(report.contains("- Group: Spring Buddy > 4. pitfalls"));
        assertTrue(report.contains("### Occurrences (3)"));
        assertTrue(report.contains("| File | Line | Message | Done |"));
        int fooLine3 = report.indexOf("| src/main/java/Foo.java | 3 |");
        int fooLine42 = report.indexOf("| src/main/java/Foo.java | 42 |");
        int barLine17 = report.indexOf("| src/main/java/Bar.java | 17 |");
        assertTrue(fooLine3 >= 0 && fooLine42 >= 0 && barLine17 >= 0);
        assertTrue(fooLine3 < fooLine42);
        assertTrue(report.contains("Without a custom executor Spring falls back to SimpleAsyncTaskExecutor."));
        assertEquals(4, countUncheckedTasks(report));
        assertFalse(report.contains("null"));
    }

    private static int countUncheckedTasks(String report) {
        int count = 0;
        for (String line : report.split("\n")) {
            if (line.startsWith("| ") && line.endsWith("| - [ ] |")) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void handlesUnknownLineAndEmptyLocations() {
        InspectionMarkdownReport.Problem noLine = new InspectionMarkdownReport.Problem("src/main/java/A.java", -1, "msg");
        InspectionMarkdownReport.Problem noFile = new InspectionMarkdownReport.Problem("", -1, "orphan problem");

        String report = InspectionMarkdownReport.build(null, null, null,
                List.of(new InspectionMarkdownReport.InspectionSection("Tool", "", "", "",
                        "", List.of(noLine, noFile))));

        assertTrue(report.contains("| src/main/java/A.java | unknown | msg |"));
        assertTrue(report.contains("| unknown | unknown | orphan problem |"));
        assertTrue(report.contains("- Project root: unknown"));
        assertTrue(report.contains("- Inspections: 1"));
        assertTrue(report.contains("- Problems: 2"));
    }

    @Test
    public void emptySectionsProduceNoProblemReport() {
        String report = InspectionMarkdownReport.build("Spring Buddy", "/tmp", "2026-10-23",
                List.of(new InspectionMarkdownReport.InspectionSection("Tool", "Tool", "ERROR", "group", "desc", List.of())));

        assertTrue(report.contains("No problems were reported."));
        assertTrue(report.contains("- Inspections: 0"));
    }

    @Test
    public void convertsHtmlDescriptionsAndMessages() {
        assertEquals("`Foo` is **bold** and *italic*",
                InspectionMarkdownReport.toSingleLine("<code>Foo</code> is <b>bold</b> and <i>italic</i>"));
        assertEquals("First paragraph\n\nSecond paragraph",
                InspectionMarkdownReport.toText("<p>First paragraph</p>\n<p>Second paragraph</p>"));
        assertEquals("a < b & c > d \"quoted\"",
                InspectionMarkdownReport.toSingleLine("a &lt; b &amp; c &gt; d &quot;quoted&quot;"));
        assertEquals("", InspectionMarkdownReport.toText(null));
        assertEquals("plain text", InspectionMarkdownReport.toSingleLine("  plain\n\ntext  "));
    }

    @Test
    public void escapesPipesAndLineBreaksInTableCells() {
        assertEquals("a \\| b text", InspectionMarkdownReport.cell("a | b\ntext"));
        assertEquals("unknown", InspectionMarkdownReport.cell(null));
        assertEquals("unknown", InspectionMarkdownReport.cell("   "));
    }
}
