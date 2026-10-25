package com.neozeon.kubedroid.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

class BackStackTest {

    private fun stackOf(vararg keys: NavKey): MutableList<NavKey> = keys.toMutableList()

    @Test
    fun singleEntryStackIsUnchanged() {
        val stack = stackOf(HomeKey)
        popBackStack(stack)
        assertEquals(listOf<NavKey>(HomeKey), stack)
    }

    @Test
    fun popsExactlyOneEntry() {
        val second = SecondKeyStub
        val stack = stackOf(HomeKey, second)
        popBackStack(stack)
        assertEquals(listOf<NavKey>(HomeKey), stack)
    }

    @Test
    fun preservesOrderOfRemainingEntries() {
        val stack = stackOf(HomeKey, SecondKeyStub, SecondKeyStub)
        popBackStack(stack)
        assertEquals(listOf<NavKey>(HomeKey, SecondKeyStub), stack)
    }
}

private object SecondKeyStub : NavKey
