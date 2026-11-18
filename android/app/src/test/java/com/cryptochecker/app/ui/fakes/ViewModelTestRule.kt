package com.cryptochecker.app.ui.fakes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Main-Dispatcher für `viewModelScope` auf der JVM ([dispatcher], gleiche Uhr wie `runTest`) und ein eigener
 * [ViewModelStore]: nach jedem Test geleert, das beendet den `viewModelScope`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTestRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    private val store = ViewModelStore()

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        store.clear()
        Dispatchers.resetMain()
    }

    private var count = 0

    /** [create] im eigenen Speicher anlegen (wie Hilt, nur ohne Graph). */
    fun <T : ViewModel> hold(create: () -> T): T {
        val vm = create()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = vm as V
        }
        return ViewModelProvider(store, factory)["vm${count++}", vm.javaClass]
    }
}
