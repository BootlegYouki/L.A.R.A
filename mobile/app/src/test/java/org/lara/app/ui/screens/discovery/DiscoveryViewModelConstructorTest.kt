package org.lara.app.ui.screens.discovery

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the launch crash where Compose's default `viewModel()` factory could not
 * instantiate [DiscoveryViewModel]: it reflectively requires an `(Application)`-only constructor on
 * an `AndroidViewModel`, and an earlier version only had a 3-arg constructor
 * (`NoSuchMethodException: <init> [class android.app.Application]`).
 *
 * This runs on the JVM (no Android runtime) by inspecting the declared constructors, so it catches
 * the regression without needing an emulator.
 */
class DiscoveryViewModelConstructorTest {

    @Test
    fun hasApplicationOnlyConstructorForComposeFactory() {
        val hasAppCtor = DiscoveryViewModel::class.java.declaredConstructors.any { ctor ->
            ctor.parameterTypes.size == 1 &&
                Application::class.java.isAssignableFrom(ctor.parameterTypes[0])
        }
        assertTrue(
            "DiscoveryViewModel must expose an (Application)-only constructor so the default " +
                "Compose viewModel() factory can create it",
            hasAppCtor,
        )
    }
}
