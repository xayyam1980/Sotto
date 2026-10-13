package com.example.watchscribe

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws the real watch app screen and saves it to docs/screens (used by the README). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w227dp-h227dp-round-watch-xhdpi")
class WatchScreenshotTest {
    @Test fun watchApp() {
        // sample "today" numbers
        Stats.addChunk(ApplicationProvider.getApplicationContext(), 2520.0)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.window.decorView.captureRoboImage("../docs/screens/watch-app.png")
    }
}
