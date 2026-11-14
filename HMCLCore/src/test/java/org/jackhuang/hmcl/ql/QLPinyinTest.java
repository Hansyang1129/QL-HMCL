/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ql;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for the bundled syllable table, the GB2312 initial letters and search matching.
@NotNullByDefault
class QLPinyinTest {

    /// Tests that the bundled Unihan table is actually packaged and parsed.
    @Test
    void loadsBundledTable() {
        assertTrue(QLPinyin.tableSize() > 6000,
                "expected the whole GB2312 hanzi area, got " + QLPinyin.tableSize());
    }

    /// Tests the initial letters of real station names of the Qinglan network.
    @Test
    void initialsOfStationNames() {
        assertEquals("NJTL", QLPinyin.initials("南际铁路"));
        assertEquals("QL", QLPinyin.initials("轻蓝"));
        assertEquals("GT", QLPinyin.initials("观塘"));
        assertEquals("JJA", QLPinyin.initials("将军澳"));
        assertEquals("DSN", QLPinyin.initials("迪士尼"));
        assertEquals("LMJ", QLPinyin.initials("落马脊"));
        assertEquals("DW", QLPinyin.initials("大围"));
    }

    /// Tests that everything which is not a level-1 Chinese character is skipped.
    @Test
    void ignoresNonChineseCharacters() {
        assertEquals("", QLPinyin.initials("Nansai Rail"));
        assertEquals("", QLPinyin.initials("123 -_.|"));
        assertEquals("", QLPinyin.initials(""));
        assertEquals(0, QLPinyin.initial('A'));
        assertEquals(0, QLPinyin.initial('0'));
        assertEquals('T', QLPinyin.initial('铁'));
    }

    /// Tests full-pinyin transcription, with and without syllable separators.
    @Test
    void writesFullPinyin() {
        assertEquals("nan ji tie lu", QLPinyin.pinyin("南际铁路", false));
        assertEquals("nanjitielu", QLPinyin.pinyin("南际铁路", true));
        assertEquals("guantang", QLPinyin.pinyin("观塘", true));
        assertEquals("qinglan", QLPinyin.pinyin("轻蓝", true));
        assertEquals("", QLPinyin.pinyin("Nansai Rail", true), "romanized names need no transcription");

        List<String> lv = Arrays.asList(QLPinyin.syllablesOf('绿'));
        assertTrue(lv.contains("lu") && lv.contains("lv"), "both spellings of the umlaut are indexed: " + lv);
    }

    /// Tests that a search key answers Chinese, romanized, full-pinyin and initial-letter queries.
    @Test
    void searchKeyMatchesAllTypingStyles() {
        String key = QLPinyin.searchKey("南际铁路", "Nansai Rail");

        assertTrue(QLPinyin.matches("南际", key), "typed Chinese");
        assertTrue(QLPinyin.matches("nansai", key), "typed romanization");
        assertTrue(QLPinyin.matches("NANSAI", key), "case is ignored");
        assertTrue(QLPinyin.matches("nanji", key), "typed full pinyin");
        assertTrue(QLPinyin.matches("nan ji", key), "typed pinyin with a separator");
        assertTrue(QLPinyin.matches("nanjitielu", key), "typed pinyin without a separator");
        assertTrue(QLPinyin.matches("njtl", key), "typed pinyin initials");
        assertTrue(QLPinyin.matches("NJ", key), "partial initials");
        assertTrue(QLPinyin.matches("", key), "empty query matches everything");
        assertTrue(QLPinyin.matches(null, key), "null query matches everything");
        assertTrue(QLPinyin.matches("rail 南际", key), "every word must hit");

        assertFalse(QLPinyin.matches("zz", key), "unrelated initials must not hit");
        assertFalse(QLPinyin.matches("静音", key), "unrelated station must not hit");
    }

    /// Tests that the merged Unihan fields keep the alternative readings of a polyphone
    /// searchable, without losing the canonical joined transcription or the initials.
    @Test
    void indexesPolyphones() {
        String key = QLPinyin.searchKey("长征路", null);

        assertTrue(QLPinyin.matches("zhangzhenglu", key), "canonical reading joined");
        assertTrue(QLPinyin.matches("zhang zheng lu", key), "canonical reading spaced");
        assertTrue(QLPinyin.matches("chang", key), "alternative reading of 长");
        assertTrue(QLPinyin.matches("czl", key), "initials follow the GB2312 ordering");

        String line = QLPinyin.searchKey("行", null);
        assertTrue(QLPinyin.matches("xing", line) && keepsBothReadings(line), "行 keeps both readings");
    }

    /// @return whether the key of 行 answers both hang and heng.
    private static boolean keepsBothReadings(String line) {
        return QLPinyin.matches("hang", line) && QLPinyin.matches("heng", line);
    }

    /// Tests that a name without a romanized part still produces a usable key, and that
    /// umlaut spellings survive into it.
    @Test
    void searchKeyWithoutSecondaryName() {
        String key = QLPinyin.searchKey("观塘", null);
        assertTrue(QLPinyin.matches("gt", key));
        assertTrue(QLPinyin.matches("guantang", key));
        assertTrue(QLPinyin.matches("观塘", key));
        assertFalse(QLPinyin.matches("kt", key));

        String lvKey = QLPinyin.searchKey("绿", null);
        assertTrue(QLPinyin.matches("lv", lvKey), "v spelling");
        assertTrue(QLPinyin.matches("lu", lvKey), "u spelling");
    }

    /// Tests the city name 重庆: the canonical GB2312 reading of 重 orders the
    /// initials, while every alternative reading of the Unihan table stays searchable.
    @Test
    void indexesCityNamePolyphone() {
        String key = QLPinyin.searchKey("重庆", null);

        assertTrue(QLPinyin.matches("zhongqing", key), "canonical joined transcription");
        assertTrue(QLPinyin.matches("zhong", key) && QLPinyin.matches("chong", key),
                "both readings of 重 are searchable");
        assertTrue(QLPinyin.matches("zq", key), "initials follow the GB2312 reading of 重");

        String syllable = QLPinyin.searchKey("重", null);
        assertTrue(QLPinyin.matches("tong", syllable), "the third reading of 重");
    }

    /// Tests that rare GB2312 level-2 characters transcribe fully but carry no initial
    /// letter, and that characters outside the GB2312 hanzi area are skipped without
    /// crashing the search.
    @Test
    void indexesRareAndUngroupedCharacters() {
        List<String> miao = Arrays.asList(QLPinyin.syllablesOf('淼'));
        assertTrue(miao.contains("miao"), "level-2 characters are transcribed: " + miao);
        assertEquals(0, QLPinyin.initial('淼'), "level-2 characters have no pronunciation group");
        assertTrue(QLPinyin.matches("miao", QLPinyin.searchKey("淼", null)),
                "full pinyin still makes rare characters searchable");

        assertEquals(0, QLPinyin.initial('鰂'), "characters outside GB2312 yield no initial");
        assertEquals("", QLPinyin.initials("鰂"), "and are simply skipped");

        String gap = QLPinyin.searchKey("鰂码头", null);
        assertTrue(QLPinyin.matches("matou", gap), "known characters around the gap still transcribe");
        assertTrue(QLPinyin.matches("ma tou", gap), "spaced query of the same");
        assertFalse(QLPinyin.matches("zaima", gap), "the ungapped character contributes nothing");
    }

    /// Tests bilingual names whose romanized part carries words beyond the initials.
    @Test
    void searchesMixedChineseAndLatinNames() {
        String key = QLPinyin.searchKey("南际站", "Nansai Road");

        assertTrue(QLPinyin.matches("南际", key));
        assertTrue(QLPinyin.matches("nansai", key), "romanized name");
        assertTrue(QLPinyin.matches("road", key), "latin words of the secondary name");
        assertTrue(QLPinyin.matches("njz road", key), "every word must hit across both channels");
        assertFalse(QLPinyin.matches("east", key));
    }
}
