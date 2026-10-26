package com.example.watchscribe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A tile tap acts once, even when the watch repeats the last clicked id on later refreshes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TileClickTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun repeatedIdIsIgnored() {
        assertTrue("first tap acts", Stats.firstTimeTileClick(ctx, "resume#1000"))
        assertFalse("same id on a refresh does nothing", Stats.firstTimeTileClick(ctx, "resume#1000"))
        assertFalse(Stats.firstTimeTileClick(ctx, "resume#1000"))
        assertTrue("a new tap on the redrawn tile acts", Stats.firstTimeTileClick(ctx, "resume#2000"))
        assertTrue("another button acts", Stats.firstTimeTileClick(ctx, "mark#2000"))
    }
}
