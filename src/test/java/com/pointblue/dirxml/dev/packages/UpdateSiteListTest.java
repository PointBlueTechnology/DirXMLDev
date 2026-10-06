package com.pointblue.dirxml.dev.packages;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

/** {@link UpdateSite#list}: what a site offers, from its {@code site.xml}. */
public class UpdateSiteListTest {

    @Test
    public void listsEveryFeatureAsAnOffer() throws IOException {
        Path dir = Files.createTempDirectory("site");
        Path xml = dir.resolve("site.xml");
        Files.writeString(xml, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<site>\n"
            + "  <feature url=\"features/NOVLADBASE.feature_2.3.0.20190101.jar\" id=\"NOVLADBASE.feature\" version=\"2.3.0.20190101\"/>\n"
            + "  <feature url=\"features/NOVLADBASE.feature_2.4.0.20200101.jar\" id=\"NOVLADBASE.feature\" version=\"2.4.0.20200101\"/>\n"
            + "  <feature url=\"features/NOVLCOMSET.feature_2.0.1.jar\" id=\"NOVLCOMSET.feature\" version=\"2.0.1\"/>\n"
            + "</site>\n");
        List<UpdateSite.Offer> offers = UpdateSite.list(xml);
        assertEquals(3, offers.size());
        assertEquals("NOVLADBASE", offers.get(0).shortName);
        assertEquals("2.3.0.20190101", offers.get(0).version);
        assertEquals("NOVLCOMSET", offers.get(2).shortName);
        assertEquals("2.0.1", offers.get(2).version);
    }
}
