package com.dramaflow.app.presentation

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RocketUiLayoutPolicyTest {
    @Test fun sevenVisibleCalendarCellsFitTheirColumns() {
        for (screenWidth in listOf(280f, 320f, 340f, 360f, 393f, 412f, 600f, 840f)) {
            for (fontScale in listOf(1f, 1.1f, 1.2f, 1.25f, 1.5f, 2f)) {
                val available = screenWidth - 64f
                if (!RocketUiLayoutPolicy.calendarNeedsScroll(available, fontScale)) {
                    assertTrue("$screenWidth/$fontScale circle must fit",
                        available / 7f >= RocketUiLayoutPolicy.calendarCircleDp(fontScale) + 4f)
                }
                assertTrue("Scrollable cells must contain their circle",
                    RocketUiLayoutPolicy.calendarCellDp(fontScale) >= RocketUiLayoutPolicy.calendarCircleDp(fontScale) + 12)
            }
        }
    }

    @Test fun enlargedFontsUseScrollableCalendar() {
        for (width in listOf(256f, 296f, 536f, 776f)) {
            assertTrue(RocketUiLayoutPolicy.calendarNeedsScroll(width, 1.25f))
            assertTrue(RocketUiLayoutPolicy.calendarNeedsScroll(width, 2f))
        }
    }

    @Test fun normalPhoneShowsAllSevenDays() {
        assertTrue(!RocketUiLayoutPolicy.calendarNeedsScroll(296f, 1f))
        assertEquals(32, RocketUiLayoutPolicy.calendarCircleDp(1f))
        assertEquals(56, RocketUiLayoutPolicy.calendarCircleDp(2f))
    }
}
