package org.tvrenamer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class FileEpisodeTest {
    private static final Logger logger = Logger.getLogger(FileEpisodeTest.class.getName());

    private static final List<EpisodeTestData> values = new ArrayList<>();

    @TempDir
    Path tempDir;

    private final UserPreferences prefs = UserPreferences.getInstance();

    private void verboseFail(String msg, Exception e) {
        String failMsg = msg + ": " + e.getClass().getName() + " ";
        String exceptionMessage = e.getMessage();
        if (exceptionMessage != null) {
            failMsg += exceptionMessage;
        } else {
            failMsg += "(no message)";
        }
        e.printStackTrace();
        fail(failMsg);
    }

    @BeforeAll
    public static void setupValues() {
        // Test that regex special characters in episode titles are included literally
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("the.baxters")
                   .properShowName("The Baxters")
                   .showId("90001")
                   .seasonNumString("5")
                   .episodeNumString("10")
                   .episodeResolution("720p")
                   .episodeTitle("$ecret Fund")
                   .episodeId("900001")
                   .replacementMask("%S [%sx%e] %t %r")
                   .documentation("makes sure regex characters are included literally in filename")
                   .expectedReplacement("The Baxters [5x10] $ecret Fund 720p")
                   .build());
        // Ensure that colons (:) don't make it into the renamed filename
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("jake.marshall.lawman")
                   .properShowName("Jake Marshall: Lawman")
                   .showId("90002")
                   .seasonNumString("1")
                   .episodeNumString("01")
                   .episodeTitle("The Way of the Badge")
                   .replacementMask("%S [%sx%e] %t")
                   .documentation("makes sure illegal characters are not included in filename")
                   .expectedReplacement("Jake Marshall- Lawman [1x1] The Way of the Badge")
                   .build());
        // Ensure that season 9 with "%0s" produces "09" not "9"
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("shadowcraft")
                   .properShowName("Shadowcraft")
                   .seasonNumString("9")
                   .episodeNumString("21")
                   .filenameSuffix(".mp4")
                   .episodeTitle("King of the Fallen")
                   .episodeId("900003")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Shadowcraft S09E21 King of the Fallen")
                   .build());
    }

    @BeforeAll
    public static void setupValuesLongName() {
        // Very long episode title that still fits in a filename
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("Flatmates")
                   .properShowName("Flatmates")
                   .seasonNumString("05")
                   .episodeNumString("08")
                   .filenameSuffix(".avi")
                   .episodeTitle("The One With The Holiday Flashbacks"
                                 + " (a.k.a. The One With All The Holidays)")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Flatmates S05E08 The One With The Holiday Flashbacks"
                                        + " (a.k.a. The One With All The Holidays)")
                   .build());
    }

    @BeforeAll
    public static void setupValuesTooLongName() {
        // Episode title too long for a filename — should be truncated
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("Shopkeepers")
                   .properShowName("Shopkeepers")
                   .seasonNumString("01")
                   .episodeNumString("05")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Dan and Rick and Jay and Silent Pete and"
                                 + " a Bunch of New Characters and Lando,"
                                 + " Take Part in a Whole Bunch of Genre Parodies"
                                 + " Including But Not Exclusive To, The Bad News League,"
                                 + " The Last Pilot, Indiana James and the Temple"
                                 + " of Gloom, Plus a HS Reunion")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Shopkeepers S01E05 Dan and Rick and Jay and Silent Pete"
                                        + " and a Bunch of New Characters and Lando, Take Pa")
                   .build());
    }

    @BeforeAll
    public static void setupValuesBadSuffix() {
        // "Junk" after final dot instead of real suffix
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("ntac")
                   .properShowName("NTAC")
                   .seasonNumString("13")
                   .episodeNumString("04")
                   .filenameSuffix(".hdtv-lol")
                   .episodeTitle("Double Trouble")
                   .episodeId("900004")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("NTAC S13E04 Double Trouble")
                   .build());
    }

    @BeforeAll
    public static void setupValues03() {
        // Slash in show name becomes dash
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("cut stitch")
                   .properShowName("Cut/Stitch")
                   .seasonNumString("6")
                   .episodeNumString("1")
                   .filenameSuffix(".mkv")
                   .episodeResolution("720p")
                   .episodeTitle("Don Hoberman")
                   .episodeId("900005")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Cut-Stitch S06E01 Don Hoberman")
                   .build());
        // Parenthetical year in show name
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("moving target 2010")
                   .properShowName("Moving Target (2010)")
                   .seasonNumString("1")
                   .episodeNumString("2")
                   .filenameSuffix(".mkv")
                   .episodeResolution("720p")
                   .episodeTitle("Rewind")
                   .episodeId("900006")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Moving Target (2010) S01E02 Rewind")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("fortress 2009")
                   .properShowName("Fortress (2009)")
                   .seasonNumString("1")
                   .episodeNumString("9")
                   .filenameSuffix(".mkv")
                   .episodeResolution("720p")
                   .episodeTitle("Little Girl Lost")
                   .episodeId("900007")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Fortress (2009) S01E09 Little Girl Lost")
                   .build());
    }

    @BeforeAll
    public static void setupValues04() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("crown 2013")
                   .properShowName("Crown (2013)")
                   .seasonNumString("1")
                   .episodeNumString("20")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Higher Ground")
                   .episodeId("900008")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Crown (2013) S01E20 Higher Ground")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("the operatives 2013")
                   .properShowName("The Operatives (2013)")
                   .seasonNumString("2")
                   .episodeNumString("10")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Yousaf")
                   .episodeId("900009")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("The Operatives (2013) S02E10 Yousaf")
                   .build());
        // Country suffix in parentheses
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("house of mirrors us")
                   .properShowName("House of Mirrors (US)")
                   .seasonNumString("1")
                   .episodeNumString("6")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Chapter 6")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("House of Mirrors (US) S01E06 Chapter 6")
                   .build());
    }

    @BeforeAll
    public static void setupValues05() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("blended family")
                   .properShowName("Blended Family")
                   .seasonNumString("5")
                   .episodeNumString("12")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Under Pressure")
                   .episodeId("900010")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Blended Family S05E12 Under Pressure")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("realm of shadows")
                   .properShowName("Realm of Shadows")
                   .seasonNumString("5")
                   .episodeNumString("1")
                   .filenameSuffix(".mp4")
                   .episodeTitle("The Wars to Come")
                   .episodeId("900011")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Realm of Shadows S05E01 The Wars to Come")
                   .build());
        // Numeric show name; colons in episode title become dashes
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("42")
                   .properShowName("42")
                   .seasonNumString("8")
                   .episodeNumString("1")
                   .filenameSuffix(".mkv")
                   .episodeResolution("720p")
                   .episodeTitle("Day 8: 4:00 P.M. - 5:00 P.M.")
                   .episodeId("900012")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("42 S08E01 Day 8- 4-00 P.M. - 5-00 P.M.")
                   .build());
    }

    @BeforeAll
    public static void setupValues06() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("42")
                   .properShowName("42")
                   .seasonNumString("7")
                   .episodeNumString("18")
                   .filenameSuffix(".mkv")
                   .episodeResolution("720p")
                   .episodeTitle("Day 7: 1:00 A.M. - 2:00 A.M.")
                   .episodeId("900013")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("42 S07E18 Day 7- 1-00 A.M. - 2-00 A.M.")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("ashford")
                   .properShowName("Ashford")
                   .seasonNumString("4")
                   .episodeNumString("7")
                   .filenameSuffix(".mkv")
                   .episodeResolution("720p")
                   .episodeTitle("Slack Tide")
                   .episodeId("900014")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Ashford S04E07 Slack Tide")
                   .build());
        // All-caps acronym show name; parenthetical in episode title
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("tac")
                   .properShowName("TAC")
                   .seasonNumString("10")
                   .episodeNumString("1")
                   .filenameSuffix(".avi")
                   .episodeTitle("Hail and Farewell, Part II (2)")
                   .episodeId("900015")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("TAC S10E01 Hail and Farewell, Part II (2)")
                   .build());
    }

    @BeforeAll
    public static void setupValues07() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("found")
                   .properShowName("Found")
                   .seasonNumString("6")
                   .episodeNumString("5")
                   .filenameSuffix(".mkv")
                   .episodeResolution("720p")
                   .episodeTitle("Lighthouse")
                   .episodeId("900016")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Found S06E05 Lighthouse")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("depot 17")
                   .properShowName("Depot 17")
                   .seasonNumString("1")
                   .episodeNumString("1")
                   .filenameSuffix(".mkv")
                   .episodeResolution("720p")
                   .episodeTitle("Pilot")
                   .episodeId("900017")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Depot 17 S01E01 Pilot")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("two river road")
                   .properShowName("Two River Road")
                   .seasonNumString("7")
                   .episodeNumString("14")
                   .filenameSuffix(".avi")
                   .episodeTitle("Family Affair")
                   .episodeId("900018")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Two River Road S07E14 Family Affair")
                   .build());
    }

    @BeforeAll
    public static void setupValues08() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("rumour mill")
                   .properShowName("Rumour Mill")
                   .seasonNumString("3")
                   .episodeNumString("15")
                   .filenameSuffix(".avi")
                   .episodeTitle("The Sixteen Year Old Question")
                   .episodeId("900019")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Rumour Mill S03E15 The Sixteen Year Old Question")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("brookfield")
                   .properShowName("Brookfield")
                   .seasonNumString("9")
                   .episodeNumString("14")
                   .filenameSuffix(".avi")
                   .episodeTitle("Conspiracy")
                   .episodeId("900020")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Brookfield S09E14 Conspiracy")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("brookfield")
                   .properShowName("Brookfield")
                   .seasonNumString("9")
                   .episodeNumString("15")
                   .filenameSuffix(".avi")
                   .episodeTitle("Escape")
                   .episodeId("900021")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Brookfield S09E15 Escape")
                   .build());
    }

    @BeforeAll
    public static void setupValues09() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("the cosmic array theory")
                   .properShowName("The Cosmic Array Theory")
                   .seasonNumString("3")
                   .episodeNumString("18")
                   .filenameSuffix(".mkv")
                   .episodeResolution("720p")
                   .episodeTitle("The Pants Alternative")
                   .episodeId("900022")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("The Cosmic Array Theory S03E18 The Pants Alternative")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("ashford")
                   .properShowName("Ashford")
                   .seasonNumString("5")
                   .episodeNumString("5")
                   .filenameSuffix(".mkv")
                   .episodeTitle("First Light")
                   .episodeId("900023")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Ashford S05E05 First Light")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("found")
                   .properShowName("Found")
                   .seasonNumString("2")
                   .episodeNumString("7")
                   .filenameSuffix(".mkv")
                   .episodeTitle("The Other 48 Days")
                   .episodeId("900024")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Found S02E07 The Other 48 Days")
                   .build());
    }

    @BeforeAll
    public static void setupValues10() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("hollywoodland")
                   .properShowName("Hollywoodland")
                   .seasonNumString("7")
                   .episodeNumString("4")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Punchline")
                   .episodeId("900025")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Hollywoodland S07E04 Punchline")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("nexus point")
                   .properShowName("Nexus Point")
                   .seasonNumString("3")
                   .episodeNumString("7")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Waning Minutes")
                   .episodeId("900026")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Nexus Point S03E07 Waning Minutes")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("deductive")
                   .properShowName("Deductive")
                   .seasonNumString("2")
                   .episodeNumString("23")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Art in the Blood")
                   .episodeId("900027")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Deductive S02E23 Art in the Blood")
                   .build());
    }

    @BeforeAll
    public static void setupValues11() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("neighbourhood dad")
                   .properShowName("Neighbourhood Dad")
                   .seasonNumString("12")
                   .episodeNumString("19")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Meg Stinks!")
                   .episodeId("900028")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Neighbourhood Dad S12E19 Meg Stinks!")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("duluth")
                   .properShowName("Duluth")
                   .seasonNumString("1")
                   .episodeNumString("1")
                   .filenameSuffix(".mp4")
                   .episodeTitle("The Crocodile's Dilemma")
                   .episodeId("900029")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Duluth S01E01 The Crocodile's Dilemma")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("gals")
                   .properShowName("Gals")
                   .seasonNumString("3")
                   .episodeNumString("11")
                   .filenameSuffix(".mp4")
                   .episodeTitle("I Saw You")
                   .episodeId("900030")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Gals S03E11 I Saw You")
                   .build());
    }

    @BeforeAll
    public static void setupValues12() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("fable")
                   .properShowName("Fable")
                   .seasonNumString("3")
                   .episodeNumString("19")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Nobody Knows the Trubel I've Seen")
                   .episodeId("900031")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Fable S03E19 Nobody Knows the Trubel I've Seen")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("new gal")
                   .properShowName("New Gal")
                   .seasonNumString("3")
                   .episodeNumString("23")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Cruise")
                   .episodeId("900032")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("New Gal S03E23 Cruise")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("doctor hale")
                   .properShowName("Doctor Hale")
                   .seasonNumString("6")
                   .episodeNumString("4")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Jungle Love")
                   .episodeId("900033")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Doctor Hale S06E04 Jungle Love")
                   .build());
    }

    @BeforeAll
    public static void setupValues13() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("progeny")
                   .properShowName("Progeny")
                   .seasonNumString("5")
                   .episodeNumString("1")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Back in the Game")
                   .episodeId("900034")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Progeny S05E01 Back in the Game")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("tin rooster")
                   .properShowName("Tin Rooster")
                   .seasonNumString("7")
                   .episodeNumString("4")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Rebel Appliance")
                   .episodeId("900035")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Tin Rooster S07E04 Rebel Appliance")
                   .build());
    }

    @BeforeAll
    public static void setupValues14() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("the cosmic array theory")
                   .properShowName("The Cosmic Array Theory")
                   .seasonNumString("7")
                   .episodeNumString("23")
                   .filenameSuffix(".mp4")
                   .episodeTitle("The Gorilla Dissolution")
                   .episodeId("900036")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("The Cosmic Array Theory S07E23 The Gorilla Dissolution")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("the good counsel")
                   .properShowName("The Good Counsel")
                   .seasonNumString("5")
                   .episodeNumString("20")
                   .filenameSuffix(".mp4")
                   .episodeTitle("The Deep Web")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("The Good Counsel S05E20 The Deep Web")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("veep")
                   .properShowName("POTUS")
                   .seasonNumString("3")
                   .episodeNumString("5")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Fishing")
                   .episodeId("900037")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("POTUS S03E05 Fishing")
                   .build());
    }

    @BeforeAll
    public static void setupValues15() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("casters of north cove")
                   .properShowName("Casters of North Cove")
                   .seasonNumString("1")
                   .episodeNumString("1")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Pilot")
                   .episodeId("900038")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Casters of North Cove S01E01 Pilot")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("depot 17")
                   .properShowName("Depot 17")
                   .seasonNumString("5")
                   .episodeNumString("4")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Savage Seduction")
                   .episodeId("900039")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Depot 17 S05E04 Savage Seduction")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("the 200")
                   .properShowName("The 200")
                   .seasonNumString("2")
                   .episodeNumString("8")
                   .filenameSuffix(".mp4")
                   .episodeTitle("Spacewalker")
                   .episodeId("900040")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("The 200 S02E08 Spacewalker")
                   .build());
    }

    @BeforeAll
    public static void setupValuesStarhopper1() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("starhopper")
                   .properShowName("Starhopper")
                   .seasonNumString("1").episodeNumString("1").episodeId("900101")
                   .filenameSuffix(".mp4").episodeTitle("Serenity")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Starhopper S01E01 Serenity").build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("starhopper")
                   .properShowName("Starhopper")
                   .seasonNumString("1").episodeNumString("2").episodeId("900102")
                   .filenameSuffix(".mp4").episodeTitle("The Cargo Run")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Starhopper S01E02 The Cargo Run").build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("starhopper")
                   .properShowName("Starhopper")
                   .seasonNumString("1").episodeNumString("3").episodeId("900103")
                   .filenameSuffix(".mp4").episodeTitle("Bushwhacked")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Starhopper S01E03 Bushwhacked").build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("starhopper")
                   .properShowName("Starhopper")
                   .seasonNumString("1").episodeNumString("4").episodeId("900104")
                   .filenameSuffix(".mp4").episodeTitle("Shindig")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Starhopper S01E04 Shindig").build());
    }

    @BeforeAll
    public static void setupValuesStarhopper2() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("starhopper").properShowName("Starhopper")
                   .seasonNumString("1").episodeNumString("5").episodeId("900105")
                   .filenameSuffix(".mp4").episodeTitle("Safe")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Starhopper S01E05 Safe").build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("starhopper").properShowName("Starhopper")
                   .seasonNumString("1").episodeNumString("6").episodeId("900106")
                   .filenameSuffix(".mp4").episodeTitle("Our Mrs. Reynolds")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Starhopper S01E06 Our Mrs. Reynolds").build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("starhopper").properShowName("Starhopper")
                   .seasonNumString("1").episodeNumString("7").episodeId("900107")
                   .filenameSuffix(".mp4").episodeTitle("Jaynestown")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Starhopper S01E07 Jaynestown").build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("starhopper").properShowName("Starhopper")
                   .seasonNumString("1").episodeNumString("8").episodeId("900108")
                   .filenameSuffix(".mp4").episodeTitle("Out of Gas")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Starhopper S01E08 Out of Gas").build());
    }

    @BeforeAll
    public static void setupValuesStarhopper3() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("starhopper").properShowName("Starhopper")
                   .seasonNumString("1").episodeNumString("9").episodeId("900109")
                   .filenameSuffix(".mp4").episodeTitle("Ariel")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Starhopper S01E09 Ariel").build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("starhopper").properShowName("Starhopper")
                   .seasonNumString("1").episodeNumString("10").episodeId("900110")
                   .filenameSuffix(".mp4").episodeTitle("War Stories")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Starhopper S01E10 War Stories").build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("starhopper").properShowName("Starhopper")
                   .seasonNumString("1").episodeNumString("11").episodeId("900111")
                   .filenameSuffix(".mp4").episodeTitle("Trash")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Starhopper S01E11 Trash").build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("starhopper").properShowName("Starhopper")
                   .seasonNumString("1").episodeNumString("12").episodeId("900112")
                   .filenameSuffix(".mp4").episodeTitle("The Message")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Starhopper S01E12 The Message").build());
    }

    @BeforeAll
    public static void setupValuesStarhopper4() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("starhopper").properShowName("Starhopper")
                   .seasonNumString("1").episodeNumString("13").episodeId("900113")
                   .filenameSuffix(".mp4").episodeTitle("Heart of Gold")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Starhopper S01E13 Heart of Gold").build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("starhopper").properShowName("Starhopper")
                   .seasonNumString("1").episodeNumString("14").episodeId("900114")
                   .filenameSuffix(".mp4").episodeTitle("Objects in Space")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Starhopper S01E14 Objects in Space").build());
    }

    @BeforeAll
    public static void setupValues17() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("hit hard")
                   .properShowName("Hit Hard")
                   .seasonNumString("1")
                   .episodeNumString("1")
                   .filenameSuffix(".mkv")
                   .episodeResolution("720p")
                   .episodeTitle("Ryan's Hit Hard, Episode 1")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Hit Hard S01E01 Ryan's Hit Hard, Episode 1")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("mephisto")
                   .properShowName("Mephisto")
                   .seasonNumString("2")
                   .episodeNumString("3")
                   .filenameSuffix(".mkv")
                   .episodeResolution("720p")
                   .episodeTitle("Sin-Eater")
                   .episodeId("900041")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Mephisto S02E03 Sin-Eater")
                   .build());
    }

    @BeforeAll
    public static void setupValues18() {
        // Apostrophe and dots in show name
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("vanguards agents of defend")
                   .properShowName("Vanguard's Agents of D.E.F.E.N.D.")
                   .seasonNumString("4")
                   .episodeNumString("3")
                   .filenameSuffix(".mkv")
                   .episodeResolution("1080p")
                   .episodeTitle("Uprising")
                   .episodeId("900042")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Vanguard's Agents of D.E.F.E.N.D. S04E03 Uprising")
                   .build());
        // Same show, different resolutions
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("shadowcraft")
                   .properShowName("Shadowcraft")
                   .seasonNumString("11")
                   .episodeNumString("22")
                   .filenameSuffix(".mkv")
                   .showId("90003")
                   .episodeId("900043")
                   .episodeResolution("1080p")
                   .episodeTitle("We Happy Few")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Shadowcraft S11E22 We Happy Few")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("shadowcraft")
                   .properShowName("Shadowcraft")
                   .seasonNumString("11")
                   .episodeNumString("22")
                   .filenameSuffix(".mkv")
                   .showId("90003")
                   .episodeId("900043")
                   .episodeResolution("720p")
                   .episodeTitle("We Happy Few")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Shadowcraft S11E22 We Happy Few")
                   .build());
    }

    @BeforeAll
    public static void setupValues19() {
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("signal void")
                   .properShowName("Signal Void")
                   .seasonNumString("1")
                   .episodeNumString("1")
                   .filenameSuffix(".mkv")
                   .episodeResolution("480p")
                   .episodeTitle("You Have to Go Inside")
                   .episodeId("900044")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("Signal Void S01E01 You Have to Go Inside")
                   .build());
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("ntac")
                   .properShowName("NTAC")
                   .seasonNumString("14")
                   .episodeNumString("4")
                   .filenameSuffix(".mkv")
                   .episodeResolution("720p")
                   .episodeTitle("Love Boat")
                   .episodeId("900045")
                   .replacementMask("%S S%0sE%0e %t")
                   .expectedReplacement("NTAC S14E04 Love Boat")
                   .build());
    }

    @BeforeAll
    public static void setupValues20() {
        // Very high season number; dot-separated replacement mask
        values.add(new EpisodeTestData.Builder()
                   .filenameShow("Home Seekers International")
                   .properShowName("Home Seekers International")
                   .seasonNumString("103")
                   .episodeNumString("02")
                   .filenameSuffix(".mkv")
                   .episodeResolution("")
                   .episodeTitle("Copenhagen Dreaming")
                   .episodeId("900046")
                   .replacementMask("%S.S%0sE%0e.%t")
                   .expectedReplacement("Home Seekers International.S103E02.Copenhagen Dreaming")
                   .build());
    }

    @Test
    public void testGetReplacementText() {
        prefs.setRenameSelected(true);
        prefs.setMoveSelected(false);
        for (EpisodeTestData data : values) {
            try {
                prefs.setRenameReplacementString(data.replacementMask);

                FileEpisode episode = data.createFileEpisode(tempDir);
                assertEquals(data.filenameSuffix, episode.getFilenameSuffix(),
                             "suffix fail on " + data.inputFilename);
                assertEquals(data.expectedReplacement, episode.getRenamedBasename(0),
                             "test which " + data.documentation);
            } catch (Exception e) {
                verboseFail("testing " + data, e);
            }
        }
    }
}
