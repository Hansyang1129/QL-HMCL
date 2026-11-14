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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/// Pinyin-aware search keys for the bilingual transit names of the MTR network.
///
/// <p>Station and route names arrive as {@code "Chinese|English"}, and players search them
/// by typing Chinese, by typing the romanized English name, by typing full pinyin
/// ({@code nansai}, {@code guantang}) or by typing pinyin initials ({@code njtl} for
/// 南际铁路). Full syllables come from a bundled table derived from the Unicode Unihan
/// {@code kMandarin} field ({@code /assets/ql/pinyin.txt}, the 6.7k characters of the
/// GB2312 hanzi area), so no dictionary dependency is needed; initials additionally come
/// for free from GB2312 itself, because its level-1 block is sorted by pronunciation. Both
/// spellings of {@code ue} ("lu" and "lv") are indexed.</p>
///
/// <p>Search keys must be precomputed per item whenever a network snapshot is loaded:
/// deriving them for every station on every keystroke would defeat the purpose of a
/// lookup table.</p>
@NotNullByDefault
public final class QLPinyin {

    /// First GB2312 code of each level-1 initial group, in ascending order.
    private static final int[] GROUP_STARTS = {
            0xB0A1, 0xB0C5, 0xB2C1, 0xB4EE, 0xB6EA, 0xB7A2, 0xB8C1, 0xB9FE, 0xBBF7,
            0xBFA6, 0xC0AC, 0xC2E8, 0xC4C3, 0xC5B6, 0xC5BE, 0xC6DA, 0xC8BB, 0xC8F6,
            0xCBFA, 0xCDDA, 0xCEF4, 0xD1B9, 0xD4D1
    };

    /// Initial letter of each group above; GB2312 has no I, U or V group.
    private static final char[] GROUP_LETTERS = {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'W', 'X', 'Y', 'Z'
    };

    /// Inclusive upper bound of the pronunciation-ordered level-1 block.
    private static final int LEVEL_1_END = 0xD7F9;

    /// Bundled syllable table, one {@code <hex codepoint>\t<syllables>} line per character.
    private static final String TABLE_RESOURCE = "/assets/ql/pinyin.txt";

    private static final @Nullable Charset GB2312 = supportedOrNull();

    /// Lazily parsed syllable table; empty when the bundled resource is unavailable.
    private static volatile @UnmodifiableView Map<Character, String> syllables = load();

    private QLPinyin() {
    }

    private static @Nullable Charset supportedOrNull() {
        try {
            return Charset.isSupported("GB2312") ? Charset.forName("GB2312") : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /// Parses the bundled table once; a failure degrades to initials-only matching.
    private static @UnmodifiableView Map<Character, String> load() {
        Map<Character, String> result = new HashMap<>(8192);
        try (InputStream stream = QLPinyin.class.getResourceAsStream(TABLE_RESOURCE)) {
            if (stream == null)
                return Map.of();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#')
                        continue;
                    int tab = line.indexOf('\t');
                    if (tab <= 0)
                        continue;
                    try {
                        int codePoint = Integer.parseInt(line.substring(0, tab), 16);
                        String value = line.substring(tab + 1).strip();
                        if (value.isEmpty() || !Character.isBmpCodePoint(codePoint))
                            continue;
                        result.put((char) codePoint, value);
                    } catch (NumberFormatException ignored) {
                        // Skip a malformed line instead of losing the whole table.
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
        return Map.copyOf(result);
    }

    /// Returns the pinyin initial letter of one character.
    ///
    /// @param c the character to look up
    /// @return the upper-case initial letter, or {@code 0} when the character is not a
    ///         level-1 GB2312 Chinese character (Latin letters, digits, punctuation,
    ///         level-2 ideographs, anything outside GB2312)
    public static char initial(char c) {
        Charset charset = GB2312;
        if (charset == null || c < 0x2E80)
            return 0;

        byte[] bytes = String.valueOf(c).getBytes(charset);
        if (bytes.length != 2 || bytes[0] == (byte) '?')
            return 0;

        int code = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
        if (code < GROUP_STARTS[0] || code > LEVEL_1_END)
            return 0;

        int index = Arrays.binarySearch(GROUP_STARTS, code);
        if (index < 0)
            index = -index - 2;
        return GROUP_LETTERS[index];
    }

    /// Builds the initial-letter string of a name.
    ///
    /// @param text the name, possibly bilingual or already romanized
    /// @return the upper-case initials; empty when nothing in {@code text} is a level-1
    ///         Chinese character
    public static String initials(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char initial = initial(text.charAt(i));
            if (initial != 0)
                result.append(initial);
        }
        return result.toString();
    }

    /// Returns the toneless syllables recorded for one character.
    ///
    /// @param c the character to look up
    /// @return its syllables separated by spaces (both the {@code u} and {@code v}
    ///         spellings where they differ), or {@code null} when the character has no entry
    public static @Nullable String[] syllablesOf(char c) {
        String value = syllables.get(c);
        return value == null ? null : value.split(" ");
    }

    /// Writes out a name in full pinyin.
    ///
    /// @param text    the name to transcribe
    /// @param joined  whether to concatenate the syllables without separators
    /// @return the lower-case transcription; empty when the text holds no known character
    public static String pinyin(String text, boolean joined) {
        StringBuilder result = new StringBuilder(text.length() * 4);
        for (int i = 0; i < text.length(); i++) {
            String[] forms = syllablesOf(text.charAt(i));
            if (forms == null || forms.length == 0)
                continue;
            if (!joined && !result.isEmpty())
                result.append(' ');
            // The first spelling is the canonical one; the rest stay in the search key only.
            result.append(forms[0]);
        }
        return result.toString().toLowerCase(Locale.ROOT);
    }

    /// Builds the single lower-case blob that search queries are matched against.
    ///
    /// <p>It holds the Chinese name, the romanized name, the pinyin initials, the spaced
    /// full pinyin and the concatenated full pinyin, separated by spaces, so one
    /// containment check covers every typing style.</p>
    ///
    /// @param primary   the Chinese part of the name, may be {@code null}
    /// @param secondary the romanized part of the name, may be {@code null}
    /// @return the lower-case search key, never {@code null}
    public static String searchKey(@Nullable String primary, @Nullable String secondary) {
        String main = primary == null ? "" : primary;
        StringBuilder key = new StringBuilder(main.length() + 64);
        key.append(main.toLowerCase(Locale.ROOT));
        if (secondary != null && !secondary.isBlank())
            key.append(' ').append(secondary.toLowerCase(Locale.ROOT));

        String initials = initials(main);
        if (!initials.isEmpty())
            key.append(' ').append(initials.toLowerCase(Locale.ROOT));

        String spaced = pinyin(main, false);
        if (!spaced.isEmpty())
            key.append(' ').append(spaced);

        String joined = pinyin(main, true);
        if (!joined.isEmpty())
            key.append(' ').append(joined);

        // Alternative readings of each character stay searchable ("zhang" as well as "chang").
        for (int i = 0; i < main.length(); i++) {
            String[] forms = syllablesOf(main.charAt(i));
            if (forms == null)
                continue;
            for (int f = 1; f < forms.length; f++)
                key.append(' ').append(forms[f].toLowerCase(Locale.ROOT));
        }
        return key.toString();
    }

    /// Tests whether a search-box fragment matches a precomputed key.
    ///
    /// @param query     the raw text typed so far; {@code null} or blank matches everything
    /// @param searchKey a value from {@link #searchKey(String, String)}
    /// @return whether every whitespace-separated word of the query occurs in the key
    public static boolean matches(@Nullable String query, String searchKey) {
        if (query == null)
            return true;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty())
            return true;
        for (String fragment : needle.split("\\s+"))
            if (!fragment.isEmpty() && !searchKey.contains(fragment))
                return false;
        return true;
    }

    /// @return how many characters the bundled syllable table covers; 0 when unavailable.
    public static int tableSize() {
        return syllables.size();
    }
}
