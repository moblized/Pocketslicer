package org.moblized.pocketslicer.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.moblized.pocketslicer.data.Bed
import kotlin.math.cos
import kotlin.math.sin

/** GLSurfaceView with touch controls: drag to orbit, pinch to zoom, two fingers to pan, double-tap to reset. */
@SuppressLint("ViewConstructor")
class PlateSurfaceView(context: Context) : GLSurfaceView(context) {
    val renderer = PlateRenderer()
    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var pointers = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.distance = (renderer.distance / detector.scaleFactor).coerceIn(30f, 3000f)
            requestRender()
            return true
        }
    })

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            renderer.resetView()
            requestRender()
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        tapDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(true)
        val count = event.pointerCount
        val focusX = (0 until count).sumOf { event.getX(it).toDouble() }.toFloat() / count
        val focusY = (0 until count).sumOf { event.getY(it).toDouble() }.toFloat() / count
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y; pointers = 1
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                pointers = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) count - 1 else count
                lastFocusX = focusX; lastFocusY = focusY
                if (pointers == 1) {
                    val keep = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(keep); lastY = event.getY(keep)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (count == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    renderer.yaw -= dx * 0.4f
                    renderer.pitch = (renderer.pitch + dy * 0.3f).coerceIn(-5f, 89f)
                    lastX = event.x; lastY = event.y
                    requestRender()
                } else if (count >= 2) {
                    val dx = focusX - lastFocusX
                    val dy = focusY - lastFocusY
                    val k = renderer.distance / height.coerceAtLeast(1) * 0.9f
                    val yr = Math.toRadians(renderer.yaw.toDouble())
                    // Screen-right and screen-up directions projected on the plate.
                    val rightX = -sin(yr).toFloat(); val rightY = cos(yr).toFloat()
                    val fwdX = -cos(yr).toFloat(); val fwdY = -sin(yr).toFloat()
                    renderer.panX -= (dx * rightX - dy * fwdX) * k
                    renderer.panY -= (dx * rightY - dy * fwdY) * k
                    lastFocusX = focusX; lastFocusY = focusY
                    requestRender()
                }
            }
        }
        return true
    }
}

@Composable
fun PlateViewer(
    mesh: StlMesh?,
    bed: Bed,
    placement: Placement?,
    dark: Boolean,
    resetKey: Any?,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { arrayOfNulls<PlateSurfaceView>(1) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> holder[0]?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val lastReset = remember { arrayOfNulls<Any>(1) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlateSurfaceView(ctx).also {
                holder[0] = it
                it.renderer.setBed(bed)
                it.renderer.resetView()
            }
        },
        update = { view ->
            view.renderer.setBed(bed)
            view.renderer.setMesh(mesh)
            view.renderer.setPlacement(placement)
            view.renderer.setDark(dark)
            if (lastReset[0] != resetKey) {
                lastReset[0] = resetKey
                view.renderer.resetView()
            }
            view.requestRender()
        },
    )
}
