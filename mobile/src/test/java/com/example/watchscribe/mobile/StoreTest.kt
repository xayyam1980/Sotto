package com.example.watchscribe.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.watchscribe.mobile.data.RecordingStore
import com.example.watchscribe.mobile.data.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.concurrent.thread

/** The note library: cached reads stay correct, and edits from two threads never undo each other. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StoreTest {
    private lateinit var ctx: Context
    private val id = "chunk_20261026_101500.wav"

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        File(RecordingStore.chunkDir(ctx), id).writeBytes(Wav.header(16_000, 32_000) + ByteArray(32_000))
        RecordingStore.invalidate()
        RecordingStore.refresh(ctx)
    }

    @Test fun editsShowUpAfterRefresh() {
        assertEquals(1, RecordingStore.items.value.size)
        RecordingStore.rename(ctx, id, "Planning")
        assertEquals("Planning", RecordingStore.byId(id)!!.title)
        RecordingStore.setStarred(ctx, listOf(id), true)
        assertTrue(RecordingStore.byId(id)!!.starred)
        RecordingStore.setPlace(ctx, id, "Office", 52.2, 21.0)
        assertEquals("Office", RecordingStore.byId(id)!!.place)
        RecordingStore.delete(ctx, listOf(id))
        assertNull(RecordingStore.byId(id))
        RecordingStore.undoDelete(ctx)
        assertEquals("Planning", RecordingStore.byId(id)!!.title)
    }

    @Test fun parallelEditsAreNotLost() {
        val a = thread { repeat(40) { i -> RecordingStore.setStarred(ctx, listOf(id), i % 2 == 1) } } // ends starred
        val b = thread { repeat(40) { i -> RecordingStore.rename(ctx, id, "Title $i") } }
        a.join(); b.join()
        RecordingStore.refresh(ctx)
        val r = RecordingStore.byId(id)!!
        assertTrue("star kept", r.starred)
        assertEquals("Title 39", r.title)
    }
}
