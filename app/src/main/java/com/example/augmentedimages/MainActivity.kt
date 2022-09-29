package com.example.augmentedimages

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentOnAttachListener
import com.google.ar.core.*
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.Sceneform
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.ViewRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.BaseArFragment
import com.google.ar.sceneform.ux.InstructionsController
import com.google.ar.sceneform.ux.TransformableNode
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class MainActivity : AppCompatActivity(), FragmentOnAttachListener,
    BaseArFragment.OnSessionConfigurationListener {
    private val futures: MutableList<CompletableFuture<Void>> = ArrayList()
    private lateinit var arFragment: ArFragment
    private var rabbitDetected = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { _: View?, insets: WindowInsetsCompat ->
            (toolbar.layoutParams as MarginLayoutParams).topMargin = insets
                .getInsets(WindowInsetsCompat.Type.systemBars()).top
            WindowInsetsCompat.CONSUMED
        }
        supportFragmentManager.addFragmentOnAttachListener(this)
        if (savedInstanceState == null) {
            if (Sceneform.isSupported(this)) {
                supportFragmentManager.beginTransaction()
                    .add(R.id.arFragment, ArFragment::class.java, null)
                    .commit()
            }
        }
    }

    override fun onAttachFragment(fragmentManager: FragmentManager, fragment: Fragment) {
        if (fragment.id == R.id.arFragment) {
            arFragment = fragment as ArFragment
            arFragment.setOnSessionConfigurationListener(this)
        }
    }

    override fun onSessionConfiguration(session: Session?, config: Config) {
        // Disable plane detection
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED

        // Images to be detected by our AR need to be added in AugmentedImageDatabase
        // This is how database is created at runtime
        // You can also prebuild database in you computer and load it directly (see: https://developers.google.com/ar/develop/java/augmented-images/guide#database)
        val database = AugmentedImageDatabase(session)
        val image = BitmapFactory.decodeResource(resources, R.drawable.world)
        // Every image has to have its own unique String identifier
        database.addImage("world", image)
        config.augmentedImageDatabase = database

        // Check for image detection
        arFragment.setOnAugmentedImageUpdateListener { augmentedImage: AugmentedImage ->
            onAugmentedImageTrackingUpdate(
                augmentedImage
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        futures.forEach(Consumer { future: CompletableFuture<Void> ->
            if (!future.isDone) future.cancel(
                true
            )
        })
    }

    private fun onAugmentedImageTrackingUpdate(augmentedImage: AugmentedImage) {
        // If there are both images already detected, for better CPU usage we do not need scan for them
        if (rabbitDetected) {
            return
        }
        if (augmentedImage.trackingState === TrackingState.TRACKING
            && augmentedImage.trackingMethod === AugmentedImage.TrackingMethod.FULL_TRACKING
        ) {

            // Setting anchor to the center of Augmented Image
            val anchorNode = AnchorNode(augmentedImage.createAnchor(augmentedImage.centerPose))
            anchorNode.parent = arFragment.arSceneView.scene


            // If rabbit model haven't been placed yet and detected image has String identifier of "rabbit"
            // This is also example of model loading and placing at runtime
            if (!rabbitDetected && augmentedImage.name.equals("world")) {
                rabbitDetected = true
                Toast.makeText(this, "Image tag detected", Toast.LENGTH_LONG).show()
                anchorNode.worldScale = Vector3(1f, 1f, 1f)
                arFragment.arSceneView.scene.addChild(anchorNode)
                futures.add(ModelRenderable.builder()
                    .setSource(this, Uri.parse("models/mech_drone.glb"))
                    .setIsFilamentGltf(true)
                    .build()
                    .thenAccept { model ->
                        val modelNode =
                            TransformableNode(arFragment.transformationSystem)


                        val rotation = modelNode.localRotation
                        val newRotation = Quaternion.axisAngle(Vector3(1f, 0f, 0f), -90f)
                        modelNode.localRotation = Quaternion.multiply(rotation, newRotation)

                        modelNode.localPosition = Vector3(0.0f, 0f, 0.06f)
//                        modelNode.scaleController.maxScale = 0.02f
//                        modelNode.scaleController.minScale = 0.01f

                        modelNode.setRenderable(model).animate(true).start()
                        anchorNode.addChild(modelNode)

                    }
                    .exceptionally {
                        Toast.makeText(this, "Unable to load rabbit model", Toast.LENGTH_LONG)
                            .show()
                        null
                    })


                futures.add(
                    ViewRenderable.builder()
                        .setView(this, R.layout.text_view)
                        .build()
                        .thenAccept { view ->
                            val viewNode = TransformableNode(arFragment.transformationSystem)
                            viewNode.apply {

                                val newRotation = Quaternion.axisAngle(Vector3(1f, 0f, 0f), -90f)
                                localRotation = Quaternion.multiply(localRotation, newRotation)
//
                                localPosition = Vector3(0.0f, 0f, 0.0f)
                                localScale = Vector3(0.7f, 0.7f, 0.7f)
                                renderable = view
                                anchorNode.addChild(viewNode)
                            }

                        }
                        .exceptionally {
                            Toast.makeText(this, "Unable to load text", Toast.LENGTH_LONG).show()
                            null
                        }
                )

            }
        }
        if (rabbitDetected) {
            arFragment.instructionsController.setEnabled(
                InstructionsController.TYPE_AUGMENTED_IMAGE_SCAN, false
            )
        }
    }
}