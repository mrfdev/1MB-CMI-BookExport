package com.mrfloris.exportbook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColorCodeTransformerTest {
    @Test
    void convertsColorsDecorationsAndResetForCmi() {
        assertEquals(
                "{#FF5555}Red&l bold&r plain",
                ColorCodeTransformer.transform("§cRed§l bold§r plain", ColorMode.CMI)
        );
    }

    @Test
    void convertsValidatedUnusualHexForEveryProfile() {
        String input = "§x§1§2§a§b§E§fText";
        assertEquals("{#12ABEF}Text", ColorCodeTransformer.transform(input, ColorMode.CMI));
        assertEquals("<#12ABEF>Text", ColorCodeTransformer.transform(input, ColorMode.MINI));
        assertEquals("&x&1&2&A&B&E&FText", ColorCodeTransformer.transform(input, ColorMode.LEGACY));
        assertEquals("Text", ColorCodeTransformer.transform(input, ColorMode.STRIP));
    }

    @Test
    void preservesMalformedAndLiteralSectionSigns() {
        String input = "Cost §q and malformed §xOops";
        assertEquals(input, ColorCodeTransformer.transform(input, ColorMode.STRIP));
        assertEquals(input, ColorCodeTransformer.transform(input, ColorMode.CMI));
    }

    @Test
    void convertsMiniMessageDecorations() {
        assertEquals(
                "<bold>Bold<underlined> underline<reset> plain",
                ColorCodeTransformer.transform("§lBold§n underline§r plain", ColorMode.MINI)
        );
    }

    @Test
    void preservesClassicCodesInLegacyAmpersandMode() {
        assertEquals(
                "&cRed&l bold&r plain",
                ColorCodeTransformer.transform("§cRed§l bold§r plain", ColorMode.LEGACY)
        );
    }
}
