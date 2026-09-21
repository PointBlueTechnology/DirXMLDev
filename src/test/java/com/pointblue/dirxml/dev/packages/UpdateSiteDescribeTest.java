package com.pointblue.dirxml.dev.packages;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.channels.NonWritableChannelException;
import org.junit.Test;

/** A fetch failure is reported by class and root cause, never as a bare "null" (the 2026-09-17 refusal). */
public class UpdateSiteDescribeTest {

    @Test
    public void messagelessExceptionNamesItsClassAndCause() {
        IOException e = new IOException();
        e.initCause(new NonWritableChannelException());
        assertEquals("IOException caused by NonWritableChannelException", UpdateSite.describe(e));
    }

    @Test
    public void messageIsKeptWithTheClass() {
        assertEquals("IOException: https://x/: HTTP 404", UpdateSite.describe(new IOException("https://x/: HTTP 404")));
    }

    @Test
    public void rootCauseMessageFillsAnEmptyOne() {
        RuntimeException e = new RuntimeException(null, new IllegalStateException("closed"));
        assertEquals("RuntimeException caused by IllegalStateException: closed", UpdateSite.describe(e));
    }
}
