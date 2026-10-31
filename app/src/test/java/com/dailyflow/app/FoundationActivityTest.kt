package com.dailyflow.app

import android.Manifest
import android.content.pm.PackageManager
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FoundationActivityTest {
    @Test fun manifestProvidesLaunchActivityAndHasNoNetworkPermission() {
        val app = RuntimeEnvironment.getApplication()
        assertNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
        assertEquals(PackageManager.PERMISSION_DENIED, app.packageManager.checkPermission(Manifest.permission.INTERNET, app.packageName))
    }
    @Test fun foundationActivityCanCreateAndDestroyOnHost() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
        (RuntimeEnvironment.getApplication() as DailyFlowApplication).database.close()
    }
}
