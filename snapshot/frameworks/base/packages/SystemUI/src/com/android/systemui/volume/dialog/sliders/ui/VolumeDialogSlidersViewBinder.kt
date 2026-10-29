/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.volume.dialog.sliders.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.LayoutRes
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.app.tracing.coroutines.launchInTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.compose.theme.PlatformTheme
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon as SysUiIcon
import com.android.systemui.haptics.slider.SliderHapticFeedbackFilter
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.res.R
import com.android.systemui.volume.AppVolumeController
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.domain.interactor.DesktopAudioTileDetailsFeatureInteractor
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderComponent
import com.android.systemui.volume.dialog.sliders.ui.compose.SliderTrack
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSlidersViewModel
import com.android.systemui.volume.dialog.ui.binder.ViewBinder
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import com.android.systemui.volume.haptics.ui.VolumeHapticsConfigsProvider
import com.android.systemui.volume.ui.compose.slider.AccessibilityParams
import com.android.systemui.volume.ui.compose.slider.Haptics
import com.android.systemui.volume.ui.compose.slider.Slider
import com.android.systemui.volume.ui.compose.slider.SliderIcon
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView

private const val APP_SLIDER_REMOVAL_DELAY_MS = 1200L
private const val APP_SLIDER_ANIMATION_DURATION_MS = 200L
private const val APP_VOLUME_MAX = 100

@VolumeDialogScope
class VolumeDialogSlidersViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogSlidersViewModel,
    private val dialogViewModel: VolumeDialogViewModel,
    private val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    private val desktopAudioTileDetailsFeatureInteractor: DesktopAudioTileDetailsFeatureInteractor,
    private val visibilityInteractor: VolumeDialogVisibilityInteractor,
) : ViewBinder {

    private val isVolumeDialogVertical = !desktopAudioTileDetailsFeatureInteractor.isEnabled()

    override fun CoroutineScope.bind(view: View) {
        val floatingSlidersContainer: ViewGroup =
            view.requireViewById(R.id.volume_dialog_floating_sliders_container)
        val appSlidersContainer: ViewGroup =
            view.requireViewById(R.id.volume_dialog_app_sliders_container)
        val mainSliderContainer: View =
            view.requireViewById(R.id.volume_dialog_main_slider_container)
        val background: View = view.requireViewById(R.id.volume_dialog_background)
        val bottomSection: View = view.requireViewById(R.id.volume_dialog_bottom_section_container)
        val topSection: View = view.requireViewById(R.id.volume_dialog_top_section_container)

        launchTraced("VDSVB#addTouchableBounds") {
            dialogViewModel.addTouchableBounds(
                mainSliderContainer,
                floatingSlidersContainer,
                appSlidersContainer,
            )
        }

        viewModel.sliders
            .onEach { uiModel ->
                bindSlider(
                    uiModel.sliderComponent,
                    mainSliderContainer,
                    arrayOf(mainSliderContainer, background, bottomSection, topSection),
                )

                val floatingSliderViewBinders = uiModel.floatingSliderComponent
                floatingSlidersContainer.ensureChildCount(
                    viewLayoutId = R.layout.volume_dialog_slider_floating,
                    count = floatingSliderViewBinders.size,
                )
                floatingSliderViewBinders.fastForEachIndexed { index, sliderComponent ->
                    val sliderContainer = floatingSlidersContainer.getChildAt(index)
                    bindSlider(sliderComponent, sliderContainer, arrayOf(sliderContainer))
                }
            }
            .launchInTraced("VDSVB#sliders", this)

        bindAppVolumeSliders(appSlidersContainer)
    }

    private fun CoroutineScope.bindSlider(
        component: VolumeDialogSliderComponent,
        sliderContainer: View,
        viewsToAnimate: Array<View>,
    ) {
        with(component.sliderViewBinder()) { bind(sliderContainer) }
        with(component.overscrollViewBinder()) { bind(sliderContainer, viewsToAnimate) }
    }

    private fun CoroutineScope.bindAppVolumeSliders(
        container: ViewGroup,
    ) {
        val context = container.context
        val handler = Handler(Looper.getMainLooper())
        val holders = LinkedHashMap<String, AppSliderHolder>()
        val pendingRemovals = LinkedHashMap<String, Runnable>()
        var initialSnapshot = true

        lateinit var controller: AppVolumeController
        controller =
            AppVolumeController(
                context,
                handler,
                AppVolumeController.Listener { apps ->
                    val animateNewSliders = !initialSnapshot
                    val activeKeys = HashSet<String>(apps.size)
                    for (app in apps) {
                        activeKeys.add(app.key)
                        pendingRemovals.remove(app.key)?.let(handler::removeCallbacks)

                        val holder =
                            holders[app.key]
                                ?: createAppSlider(
                                        container = container,
                                        app = app,
                                        controller = controller,
                                        animate = animateNewSliders,
                                    )
                                    .also { holders[app.key] = it }
                        holder.volume.value = app.volume
                    }

                    val disappeared = holders.keys.filterNot(activeKeys::contains)
                    for (key in disappeared) {
                        if (pendingRemovals.containsKey(key)) {
                            continue
                        }
                        val removal =
                            Runnable {
                                pendingRemovals.remove(key)
                                holders.remove(key)?.let { holder ->
                                    animateAppSliderRemoval(container, holder)
                                }
                            }
                        pendingRemovals[key] = removal
                        handler.postDelayed(removal, APP_SLIDER_REMOVAL_DELAY_MS)
                    }
                    initialSnapshot = false
                },
            )

        controller.start()

        coroutineContext[Job]?.invokeOnCompletion {
            handler.post {
                pendingRemovals.values.forEach(handler::removeCallbacks)
                pendingRemovals.clear()
                controller.stop()
                holders.clear()
            }
        }
    }

    private fun createAppSlider(
        container: ViewGroup,
        app: AppVolumeController.AppState,
        controller: AppVolumeController,
        animate: Boolean,
    ): AppSliderHolder {
        val context = container.context
        val root =
            LayoutInflater.from(context)
                .inflate(R.layout.volume_dialog_slider_floating, container, false)
        // Fill the app-volume column vertically without changing stock sliders.
        // The app icon remains inside the bottom section of this pill.
        if (isVolumeDialogVertical) {
            root.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        val composeView: ComposeView = root.requireViewById(R.id.volume_dialog_slider)
        val volume = MutableStateFlow(app.volume)
        val visuals = loadAppVisuals(context, app.packageName)
        // Keep the app icon in the dedicated bottom section in vertical mode.
        // It must stay outside SliderTrack so the original app drawable is not
        // recolored by active/inactive track state.


        composeView.setContent {
            PlatformTheme {
                AppVolumeSlider(
                    volume = volume,
                    label = visuals.label,
                    icon = visuals.icon,
                    drawable = visuals.drawable,
                    showIconInTrack = !isVolumeDialogVertical,
                    isVolumeDialogVertical = isVolumeDialogVertical,
                    hapticsViewModelFactory = hapticsViewModelFactory,
                    onVolumeChanged = { newVolume ->
                        volume.value = newVolume
                        visibilityInteractor.resetDismissTimeout()
                        controller.setAppVolume(app.key, newVolume)
                    },
                )
            }
        }

        if (animate) {
            root.alpha = 0f
            if (isVolumeDialogVertical) {
                root.translationX = 12.dpToPx(context)
            } else {
                root.translationY = -12.dpToPx(context)
            }
        } else {
            root.alpha = 1f
            root.translationX = 0f
            root.translationY = 0f
        }
        container.addView(root)
        if (animate) {
            root.animate()
                .alpha(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(APP_SLIDER_ANIMATION_DURATION_MS)
                .start()
        }

        return AppSliderHolder(root = root, volume = volume)
    }

    private fun animateAppSliderRemoval(
        container: ViewGroup,
        holder: AppSliderHolder,
    ) {
        holder.root
            .animate()
            .alpha(0f)
            .translationX(
                if (isVolumeDialogVertical) {
                    12.dpToPx(holder.root.context)
                } else {
                    0f
                }
            )
            .translationY(
                if (isVolumeDialogVertical) {
                    0f
                } else {
                    -12.dpToPx(holder.root.context)
                }
            )
            .setDuration(APP_SLIDER_ANIMATION_DURATION_MS)
            .withEndAction {
                if (holder.root.parent === container) {
                    container.removeView(holder.root)
                }
            }
            .start()
    }

    private data class AppSliderHolder(
        val root: View,
        val volume: MutableStateFlow<Int>,
    )

    private data class AppVisuals(
        val label: String,
        val icon: Icon.Loaded,
        val drawable: Drawable,
    )

    private fun loadAppVisuals(context: Context, packageName: String): AppVisuals {
        val packageManager = context.packageManager
        val applicationInfo =
            runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
        val label =
            applicationInfo?.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() }
                ?: packageName
        val drawable = applicationInfo?.loadIcon(packageManager) ?: packageManager.defaultActivityIcon
        return AppVisuals(
            label = label,
            icon = Icon.Loaded(drawable, ContentDescription.Loaded(label)),
            drawable = drawable,
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppVolumeSlider(
    volume: MutableStateFlow<Int>,
    label: String,
    icon: Icon.Loaded,
    drawable: Drawable,
    showIconInTrack: Boolean,
    isVolumeDialogVertical: Boolean,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    onVolumeChanged: (Int) -> Unit,
) {
    val currentVolume = volume.collectAsStateWithLifecycle().value

    val colors =
        SliderDefaults.colors(
            activeTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledActiveTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )

    val interactionSource =
        remember { MutableInteractionSource() }

    /*
     * Desktop / horizontal mode keeps the existing stock-style behavior.
     */
    if (!isVolumeDialogVertical) {
        Slider(
            value = currentVolume.toFloat(),
            valueRange = 0f..APP_VOLUME_MAX.toFloat(),
            onValueChanged = {
                onVolumeChanged(
                    it.roundToInt()
                        .coerceIn(0, APP_VOLUME_MAX)
                )
            },
            onValueChangeFinished = {},
            isEnabled = true,
            isReverseDirection = true,
            isVertical = false,
            colors = colors,
            interactionSource = interactionSource,
            haptics =
                Haptics.Enabled(
                    hapticsViewModelFactory = hapticsViewModelFactory,
                    hapticConfigs =
                        VolumeHapticsConfigsProvider.continuousConfigs(
                            SliderHapticFeedbackFilter()
                        ),
                    orientation = Orientation.Horizontal,
                ),
            stepDistance = 1f,
            track = { sliderState ->
                SliderTrack(
                    sliderState,
                    colors = colors,
                    isEnabled = true,
                    isVertical = false,
                    activeTrackEndIcon =
                        if (showIconInTrack) {
                            { iconsState ->
                                SliderIcon(
                                    icon = {
                                        SysUiIcon(
                                            icon = icon,
                                            tint = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    isVisible =
                                        !iconsState.isInactiveTrackEndIconVisible,
                                )
                            }
                        } else {
                            null
                        },
                    inactiveTrackEndIcon =
                        if (showIconInTrack) {
                            { iconsState ->
                                SliderIcon(
                                    icon = {
                                        SysUiIcon(
                                            icon = icon,
                                            tint = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    isVisible =
                                        iconsState.isInactiveTrackEndIconVisible,
                                )
                            }
                        } else {
                            null
                        },
                )
            },
            thumb = { sliderState, interactions ->
                SliderDefaults.Thumb(
                    sliderState = sliderState,
                    interactionSource = interactions,
                    enabled = true,
                    colors = colors,
                    thumbSize = DpSize(4.dp, 52.dp),
                )
            },
            accessibilityParams =
                AccessibilityParams(
                    contentDescription =
                        androidx.compose.ui.platform.LocalContext.current
                            .getString(
                                R.string.app_volume_slider_content_description,
                                label,
                            )
                ),
        )

        return
    }

    /*
     * Phone / vertical mode:
     *
     * ONE floating pill:
     *
     * ┌───────────┐
     * │           │
     * │  slider   │
     * │           │
     * ├───────────┤
     * │ app icon  │
     * └───────────┘
     *
     * The background comes from volume_dialog_slider_floating.xml,
     * therefore the lower icon section is part of the SAME card.
     */

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(
                        start = 4.dp,
                        end = 4.dp,
                        top = 4.dp,
                        bottom = 0.dp,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Slider(
                value = currentVolume.toFloat(),
                valueRange = 0f..APP_VOLUME_MAX.toFloat(),
                onValueChanged = {
                    onVolumeChanged(
                        it.roundToInt()
                            .coerceIn(0, APP_VOLUME_MAX)
                    )
                },
                onValueChangeFinished = {},
                isEnabled = true,
                isReverseDirection = true,
                isVertical = true,
                colors = colors,
                interactionSource = interactionSource,
                haptics =
                    Haptics.Enabled(
                        hapticsViewModelFactory = hapticsViewModelFactory,
                        hapticConfigs =
                            VolumeHapticsConfigsProvider.continuousConfigs(
                                SliderHapticFeedbackFilter()
                            ),
                        orientation = Orientation.Vertical,
                    ),
                stepDistance = 1f,
                track = { sliderState ->
                    SliderTrack(
                        sliderState,
                        colors = colors,
                        isEnabled = true,
                        isVertical = true,

                        // Never put the app icon into the vertical track.
                        activeTrackEndIcon = null,
                        inactiveTrackEndIcon = null,
                    )
                },
                thumb = { sliderState, interactions ->
                    SliderDefaults.Thumb(
                        sliderState = sliderState,
                        interactionSource = interactions,
                        enabled = true,
                        colors = colors,
                        thumbSize = DpSize(52.dp, 4.dp),
                    )
                },
                accessibilityParams =
                    AccessibilityParams(
                        contentDescription =
                            androidx.compose.ui.platform.LocalContext.current
                                .getString(
                                    R.string.app_volume_slider_content_description,
                                    label,
                                )
                    ),
                modifier = Modifier.fillMaxSize(),
            )
        }

        /*
         * Same separator relationship as the stock lower controls.
         */
        Box(
            modifier =
                Modifier
                    .padding(horizontal = 8.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(
                            alpha = 0.24f
                        )
                    )
        )

        /*
         * Bottom section is INSIDE the same floating pill.
         *
         * Android ImageView is intentional:
         * it guarantees the actual application Drawable is rendered without
         * Material/Slider tinting.
         */
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.size(30.dp),
                factory = { context ->
                    ImageView(context).apply {
                        scaleType =
                            ImageView.ScaleType.CENTER_INSIDE

                        isClickable = false
                        isFocusable = false

                        imageTintList = null
                        clearColorFilter()
                    }
                },
                update = { imageView ->
                    val iconDrawable =
                        drawable.constantState
                            ?.newDrawable(imageView.resources)
                            ?.mutate()
                            ?: drawable.mutate()

                    imageView.imageTintList = null
                    imageView.clearColorFilter()
                    imageView.setImageDrawable(iconDrawable)
                },
            )
        }
    }
}

private fun ViewGroup.ensureChildCount(@LayoutRes viewLayoutId: Int, count: Int) {
    val childCountDelta = childCount - count
    when {
        childCountDelta > 0 -> {
            removeViews(0, childCountDelta)
        }
        childCountDelta < 0 -> {
            val inflater = LayoutInflater.from(context)
            repeat(-childCountDelta) { inflater.inflate(viewLayoutId, this, true) }
        }
    }
}

private fun Int.dpToPx(context: Context): Float = this * context.resources.displayMetrics.density
