package org.tvrenamer.controller.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.tvrenamer.controller.util.StringUtils.*;

import org.junit.jupiter.api.Test;

public class StringUtilsTest {

    @Test
    public void testSanitiseTitleBackslash() {
        assertEquals("Test-", sanitiseTitle("Test\\"));
        assertEquals("Test-", replaceIllegalCharacters("Test\\"));
    }

    @Test
    public void testSanitiseTitleForwardSlash() {
        assertEquals("Test-", sanitiseTitle("Test/"));
        assertEquals("Test-", replaceIllegalCharacters("Test/"));
    }

    @Test
    public void testSanitiseTitleColon() {
        assertEquals("Test-", sanitiseTitle("Test:"));
        assertEquals("Test-", replaceIllegalCharacters("Test:"));
    }

    @Test
    public void testSanitiseTitlePipe() {
        assertEquals("Test-", sanitiseTitle("Test|"));
        assertEquals("Test-", replaceIllegalCharacters("Test|"));
    }

    @Test
    public void testSanitiseTitleAsterisk() {
        assertEquals("Test-", sanitiseTitle("Test*"));
        assertEquals("Test-", replaceIllegalCharacters("Test*"));
    }

    @Test
    public void testSanitiseTitleQuestionMark() {
        assertEquals("Test", sanitiseTitle("Test?"));
        assertEquals("Test", replaceIllegalCharacters("Test?"));
    }

    @Test
    public void testSanitiseTitleGreaterThan() {
        assertEquals("Test", sanitiseTitle("Test>"));
        assertEquals("Test", replaceIllegalCharacters("Test>"));
    }

    @Test
    public void testSanitiseTitleLessThan() {
        assertEquals("Test", sanitiseTitle("Test<"));
        assertEquals("Test", replaceIllegalCharacters("Test<"));
    }

    @Test
    public void testSanitiseTitleDoubleQuote() {
        assertEquals("Test'", sanitiseTitle("Test\""));
        assertEquals("Test'", replaceIllegalCharacters("Test\""));
    }

    @Test
    public void testSanitiseTitleTilde() {
        assertEquals("Test'", sanitiseTitle("Test`"));
        assertEquals("Test'", replaceIllegalCharacters("Test`"));
    }

    @Test
    public void testSanitiseTitleTrim() {
        assertEquals("Test", sanitiseTitle("  <Test> \n"));
        assertEquals("  Test \n", replaceIllegalCharacters("  <Test> \n"));
    }

    @Test
    public void testSanitiseTitleOnlyTrim() {
        // The whitespace in between the words should NOT be removed.
        assertEquals("Test Two", sanitiseTitle(" \t<Test Two> "));
        assertEquals(
            " \tTest Two ",
            replaceIllegalCharacters(" \t<Test Two> ")
        );
    }

    @Test
    public void testSanitiseTitleEmpty() {
        assertEquals("", sanitiseTitle(""));
        assertEquals("", replaceIllegalCharacters(""));
    }

    @Test
    public void testSanitiseTitleBlank() {
        assertEquals("", sanitiseTitle("   "));
        assertEquals("   ", replaceIllegalCharacters("   "));
    }

    @Test
    public void testUnquoteStringNormal() {
        assertEquals("Season ", unquoteString("\"Season \""));
    }

    @Test
    public void testUnquoteStringUnbalanced() {
        assertEquals("Season ", unquoteString("Season \""));
        assertEquals("Season ", unquoteString("\"Season "));
    }

    @Test
    public void testUnquoteStringNoQuotes() {
        assertEquals("Season ", unquoteString("Season "));
    }

    @Test
    public void testUnquoteStringShort() {
        assertEquals("", unquoteString(""));
        assertEquals(" ", unquoteString(" "));
        assertEquals("s", unquoteString("s"));
    }

    @Test
    public void testUnquoteStringWeird() {
        assertEquals("", unquoteString("\""));
        assertEquals("", unquoteString("\"\""));
        assertEquals("\"foo", unquoteString("\"\"foo"));
        assertEquals("foo\"", unquoteString("\"foo\"\""));
    }

    @Test
    public void testAccessibleMap() {
        assertEquals("-", SANITISE.get('/'));
    }

    @Test
    public void testUnmodifiableMap() {
        try {
            SANITISE.put('/', "_");
            fail("was able to modify map that is supposed to be unmodifiable");
        } catch (Exception e) {
            // expected result
        }
    }

    @Test
    public void testZeroPad() {
        assertEquals("00", zeroPadTwoDigits(0));
        assertEquals("08", zeroPadTwoDigits(8));
        assertEquals("09", zeroPadTwoDigits(9));
        assertEquals("10", zeroPadTwoDigits(10));
        assertEquals("11", zeroPadTwoDigits(11));
        assertEquals("100", zeroPadTwoDigits(100));
    }

    @Test
    public void testRemoveLast() {
        // Straightforward removal; note does not remove punctuation/separators
        assertEquals("foo..baz", removeLast("foo.bar.baz", "bar"));

        // Case-insensitive: match "bar" removes "Bar" from the original
        assertEquals("Foo..Baz", removeLast("Foo.Bar.Baz", "bar"));

        // Like the name says, the method only removes the last instance
        assertEquals("bar.foo..baz", removeLast("bar.foo.bar.baz", "bar"));

        // Doesn't have to be delimited
        assertEquals("emassment", removeLast("embarassment", "bar"));

        // Doesn't necessarily replace anything
        assertEquals("Foo.Schmar.baz", removeLast("Foo.Schmar.baz", "bar"));

        // Case-insensitive: match with mixed case works too
        assertEquals("Foo..Baz", removeLast("Foo.Bar.Baz", "Bar"));
    }

    @Test
    public void testGetExtension() {
        assertEquals(".mkv", getExtension("vexlar.407.720p.hdtv.x264-sys.mkv"));
        String shield =
            "Phantoms.Agents.of.G.U.A.R.D.S.S04E03.1080p.HDTV.x264-KILLERS[ettv].avi";
        assertEquals(".avi", getExtension(shield));
        assertEquals(".mp4", getExtension("/TV/Vexlar/S05E05 First Blood.mp4"));
        assertEquals("", getExtension("Preternatural"));
    }

    @Test
    public void testDotTitle() {
        // This is the simplest example of how a naive approach might fail
        assertEquals("If.I.Do", makeDotTitle("If I Do "));
        assertEquals("If.I.Do...I.Do", makeDotTitle("If I Do... I Do"));
        assertEquals("#HappyHolograms", makeDotTitle("#HappyHolograms"));
        assertEquals(
            "'Twas.the.Nightmare.Before.Christmas",
            makeDotTitle("'Twas the Nightmare Before Christmas")
        );
        assertEquals("1%", makeDotTitle("1%"));
        assertEquals("200(1)", makeDotTitle("200 (1)"));
        assertEquals(
            "Helen.Keller!The.Musical",
            makeDotTitle("Helen Keller! The Musical")
        );
        assertEquals(
            "And.in.Case.I.Don't.See.Ya",
            makeDotTitle("And in Case I Don't See Ya")
        );
        assertEquals(
            "Are.You.There.God.It's.Me,Jesus",
            makeDotTitle("Are You There God It's Me, Jesus")
        );
        assertEquals(
            "The.Return.of.Dorothy's.Ex(a.k.a.Stan's.Return)",
            makeDotTitle("The Return of Dorothy's Ex (a.k.a. Stan's Return)")
        );
        assertEquals(
            "Girls.Just.Wanna.Have.Fun...Before.They.Die",
            makeDotTitle("Girls Just Wanna Have Fun... Before They Die")
        );
        assertEquals(
            "Terrance&Phillip.in'Not.Without.My.Anus'",
            makeDotTitle("Terrance & Phillip in 'Not Without My Anus'")
        );
        assertEquals("B&B's.B'n.B", makeDotTitle("B & B's B'n B"));
        assertEquals("AWESOM-O", makeDotTitle("AWESOM-O"));
        assertEquals(
            "Coon.2-Hindsight(1)",
            makeDotTitle("Coon 2 - Hindsight (1)")
        );
        assertEquals("Class.Pre-Union", makeDotTitle("Class Pre-Union"));
        assertEquals("D-Yikes!", makeDotTitle("D-Yikes!"));
        assertEquals(
            "Ebbtide.VI-The.Wrath.of.Stan",
            makeDotTitle("Ebbtide VI - The Wrath of Stan")
        );
        assertEquals(
            "Goth.Kids.3-Dawn.of.the.Posers",
            makeDotTitle("Goth Kids 3 - Dawn of the Posers")
        );
        assertEquals(
            "Jerry-Portrait.of.a.Video.Junkie",
            makeDotTitle("Jerry - Portrait of a Video Junkie")
        );
        assertEquals("Musso-a.Wedding", makeDotTitle("Musso - a Wedding"));
        assertEquals(
            "Poetic.License-An.Ode.to.Holden.Caulfield",
            makeDotTitle("Poetic License - An Ode to Holden Caulfield")
        );
        assertEquals(
            "Sixteen.Candles.and.400-lb.Men",
            makeDotTitle("Sixteen Candles and 400-lb. Men")
        );
        assertEquals(
            "Slapsgiving.2-Revenge.of.the.Slap",
            makeDotTitle("Slapsgiving 2 - Revenge of the Slap")
        );
        assertEquals(
            "Valentine's.Day.4-Twisted.Sister",
            makeDotTitle("Valentine's Day 4 - Twisted Sister")
        );
        assertEquals(
            "Ro\\$e.Love\\$Mile\\$",
            makeDotTitle("Ro\\$e Love\\$ Mile\\$")
        );
        assertEquals(
            "Believe.it.or.Not,Joe's.Walking.on.Air",
            makeDotTitle("Believe it or Not, Joe's Walking on Air")
        );
        assertEquals("Eek,A.Penis!", makeDotTitle("Eek, A Penis!"));
        assertEquals(
            "I.Love.You,Donna.Karan(1)",
            makeDotTitle("I Love You, Donna Karan (1)")
        );
    }

    @Test
    public void testReplacePunctuation() {
        assertEquals(
            "Phantoms Agents of GUARDS",
            replacePunctuation("Phantom's.Agents.of.G.U.A.R.D.S.")
        );
        assertEquals(
            "Phantoms Agents of GUARDS",
            replacePunctuation("Phantom's Agents of G.U.A.R.D.S.")
        );
        assertEquals(
            "Phantoms Agents of GUARDS",
            replacePunctuation("Phantom's Agents of GUARDS")
        );
        assertEquals(
            "Neon Drift The Lost Frontier",
            replacePunctuation("Neon Drift: The Lost Frontier")
        );
        assertEquals(
            "Danny Farlows Flying Circus",
            replacePunctuation("Danny Farlow's Flying Circus")
        );
        assertEquals(
            "Coupled with Abandon",
            replacePunctuation("Coupled... with Abandon")
        );
        assertEquals(
            "Hex The Demon and Gus",
            replacePunctuation("Hex, The Demon and Gus")
        );
        assertEquals(
            "Whats Cracking",
            replacePunctuation("What's Cracking!!")
        );
        assertEquals(
            "Precinct Four Four",
            replacePunctuation("Precinct Four-Four")
        );
        assertEquals(
            "Riddle She Found",
            replacePunctuation("Riddle, She Found")
        );
        assertEquals(
            "Riddle She Found",
            replacePunctuation("Riddle-She-Found")
        );
        assertEquals("Grey Falcon PI", replacePunctuation("Grey Falcon, P.I."));
        assertEquals(
            "Wilkins & Thorne",
            replacePunctuation("Wilkins & Thorne")
        );
        assertEquals(
            "Sit Down Move On",
            replacePunctuation("Sit Down, Move On")
        );
        assertEquals("The Real OBriens", replacePunctuation("The Real O'Briens"));
        assertEquals("The Bureau (US)", replacePunctuation("The Bureau (US)"));
        assertEquals("That 90s Crowd", replacePunctuation("That '90s Crowd"));
        assertEquals("Eerie Arcadia", replacePunctuation("Eerie, Arcadia"));
        assertEquals("Midnight Dad", replacePunctuation("Midnight Dad!"));
        assertEquals("Teds Burdens", replacePunctuation("Ted's Burdens"));
        assertEquals("Elm vs Wild", replacePunctuation("Elm vs. Wild"));
        assertEquals("The Q Files", replacePunctuation("The Q-Files"));
        assertEquals("Lore Busters", replacePunctuation("LoreBusters"));
        assertEquals("Greyish", replacePunctuation("Grey-ish"));
        assertEquals("40 Flock", replacePunctuation("40Flock"));
        assertEquals("Dr Cobalt", replacePunctuation("Dr. Cobalt"));
        assertEquals("Starling", replacePunctuation("Star-ling"));
        assertEquals("cold signal theory", replacePunctuation("cold-signal-theory"));
        assertEquals("midnight dad", replacePunctuation("midnight-dad"));
        assertEquals(
            "Vortex A Warped Time Trilogy",
            replacePunctuation("Vortex.A.Warped.Time.Trilogy.")
        );
        assertEquals(
            "How We Lost Our Rhythm",
            replacePunctuation("How.We.Lost.Our.Rhythm.")
        );
    }

    @Test
    public void testReplacePunctuation2() {
        // The apostrophe (single quote) is treated specially: simply removed
        assertEquals("Elm Bark", replacePunctuation("El'm Bark"));
        // Parentheses and ampersand are left alone
        assertEquals("Elm (Bark)", replacePunctuation("Elm (Bark)"));
        assertEquals("Elm & Bark", replacePunctuation("Elm & Bark"));
        // Other punctuation gets replaced by a space
        assertEquals("Elm Bark", replacePunctuation("Elm\\Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm\"Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm!Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm#Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm$Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm%Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm*Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm+Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm,Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm-Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm.Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm/Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm:Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm;Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm<Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm=Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm>Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm?Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm@Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm[Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm]Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm^Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm_Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm`Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm{Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm|Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm}Bark"));
        assertEquals("Elm Bark", replacePunctuation("Elm~Bark"));
    }

    /**
     * Test trimFoundShow.  It should trim separator characters (space, hyphen,
     * dot, underscore) from the beginning and end of the string, but not change
     * the middle, substantive part at all.
     *
     */
    @Test
    public void testTrimFoundShow() {
        assertEquals("Dr. Foo's Man-Pig", trimFoundShow("Dr. Foo's Man-Pig"));
        assertEquals("Dr. Foo's Man-Pig", trimFoundShow("Dr. Foo's Man-Pig "));
        assertEquals("Dr. Foo's_Man-Pig", trimFoundShow("Dr. Foo's_Man-Pig_"));
        assertEquals(
            "Dr. Foo's_Man-Pig",
            trimFoundShow("  Dr. Foo's_Man-Pig_")
        );
    }

    /**
     * Helper method.  We used to take the substring produced by the parser (the
     * "filename show" or "found show") and pass it to makeQueryString to get the
     * string to send to the provider.  Now, we're inserting another step in there:
     * trimFoundShow.  But this is not intended to change the strings we send to
     * the provider, in anyway.  So this method validates that.  The result of
     * calling makeQueryString on the trimmed string, should be identical to calling
     * makeQueryString on the original string.
     *
     * @param input
     *   any String, but intended to be the part of a filename that we think
     *   represents the name of the show
     *
     */
    private void assertTrimSafe(String input) {
        assertEquals(
            makeQueryString(input),
            makeQueryString(trimFoundShow(input))
        );
    }

    /**
     * Now that we have a method to verify that trimFoundShow is not changing the
     * results of makeQueryString, run it through all the sample data we used in
     * {@link #testReplacePunctuation} and {@link #testTrimFoundShow}.
     *
     */
    @Test
    public void testTrimForQueryString() {
        assertTrimSafe("Phantom's.Agents.of.G.U.A.R.D.S.");
        assertTrimSafe("Phantom's Agents of G.U.A.R.D.S.");
        assertTrimSafe("Phantom's Agents of GUARDS");
        assertTrimSafe("Neon Drift: The Lost Frontier");
        assertTrimSafe("Danny Farlow's Flying Circus");
        assertTrimSafe("Coupled... with Abandon");
        assertTrimSafe("Hex, The Demon and Gus");
        assertTrimSafe("What's Cracking!!");
        assertTrimSafe("Precinct Four-Four");
        assertTrimSafe("Riddle, She Found");
        assertTrimSafe("Riddle-She-Found");
        assertTrimSafe("Grey Falcon, P.I.");
        assertTrimSafe("Wilkins & Thorne");
        assertTrimSafe("Sit Down, Move On");
        assertTrimSafe("The Real O'Briens");
        assertTrimSafe("The Bureau (US)");
        assertTrimSafe("That '90s Crowd");
        assertTrimSafe("Eerie, Arcadia");
        assertTrimSafe("Midnight Dad!");
        assertTrimSafe("Ted's Burdens");
        assertTrimSafe("Elm vs. Wild");
        assertTrimSafe("The Q-Files");
        assertTrimSafe("LoreBusters");
        assertTrimSafe("Grey-ish");
        assertTrimSafe("40Flock");
        assertTrimSafe("Dr. Cobalt");
        assertTrimSafe("Star-ling");
        assertTrimSafe("cold-signal-theory");
        assertTrimSafe("midnight-dad");
        assertTrimSafe("Vortex.A.Warped.Time.Trilogy.");
        assertTrimSafe("How.We.Lost.Our.Rhythm.");
        assertTrimSafe("Dr. Foo's Man-Pig");
        assertTrimSafe("Dr. Foo's Man-Pig ");
        assertTrimSafe("Dr. Foo's_Man-Pig_");
        assertTrimSafe("  Dr. Foo's_Man-Pig_");
    }

    @Test
    public void testEscapeXml() {
        assertEquals("", escapeXml(null));
        assertEquals("hello", escapeXml("hello"));
        assertEquals("&amp;", escapeXml("&"));
        assertEquals("&lt;tag&gt;", escapeXml("<tag>"));
        assertEquals("&quot;quoted&quot;", escapeXml("\"quoted\""));
        assertEquals("it&apos;s", escapeXml("it's"));
        assertEquals(
            "Tom &amp; Jerry &lt;2023&gt;",
            escapeXml("Tom & Jerry <2023>")
        );
    }

    @Test
    public void testGetBaseName() {
        assertEquals("vexlar.407.720p.hdtv.x264-sys", getBaseName("vexlar.407.720p.hdtv.x264-sys.mkv"));
        assertEquals("Preternatural", getBaseName("Preternatural"));
        assertEquals("test", getBaseName("test.mp4"));
        assertEquals(".hidden", getBaseName(".hidden"));
    }
}
