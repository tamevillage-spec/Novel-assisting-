package com.novelassist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.novelassist.ui.AppViewModel
import com.novelassist.ui.NovelAssistRoot

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    /** The screen state, exposed for the UI tests. */
    internal val viewModel: AppViewModel get() = vm

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NovelAssistRoot(vm) }
    }

    override fun onStart() {
        super.onStart()
        vm.onAppForeground()
    }

    override fun onStop() {
        vm.onAppBackground()
        super.onStop()
    }
}
