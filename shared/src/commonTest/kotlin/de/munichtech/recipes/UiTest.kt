package de.munichtech.recipes

/**
 * Base class for Compose UI tests in commonTest. On Android host tests the actual class carries
 * the Robolectric runner, so the same test runs on the JVM without an emulator.
 */
expect abstract class UiTest()
