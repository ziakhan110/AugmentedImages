package com.example.augmentedimages

import android.app.DownloadManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Button
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
import com.google.ar.sceneform.Sceneform
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.ViewRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.BaseArFragment
import com.google.ar.sceneform.ux.InstructionsController
import com.google.ar.sceneform.ux.TransformableNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity(), FragmentOnAttachListener,
    BaseArFragment.OnSessionConfigurationListener {
    private val futures: MutableList<CompletableFuture<Void>> = ArrayList()
    private lateinit var arFragment: ArFragment
    private var rabbitDetected = false
    private var session: Session? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        val exitButton = findViewById<Button>(R.id.exitButton)
        exitButton.setOnClickListener {
            try {
                arFragment.destroy()
                session?.close()
                File(cacheDir.absolutePath, "model.glb").deleteRecursively()
                exitProcess(0)
            } catch (e: Exception) {
                Toast.makeText(this, "Error closing session", Toast.LENGTH_LONG)
            }
        }
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
        downloadModel("https://firebasestorage.googleapis.com/v0/b/augmentedimages-e27ae.appspot.com/o/mech_drone.glb?alt=media&token=4c39cfa8-d257-488c-9389-37d1be80fcc2")
        Toast.makeText(this, "Downloading model", Toast.LENGTH_LONG)
    }

    override fun onAttachFragment(fragmentManager: FragmentManager, fragment: Fragment) {
        if (fragment.id == R.id.arFragment) {
            arFragment = fragment as ArFragment
            arFragment.setOnSessionConfigurationListener(this)
        }
    }

    override fun onSessionConfiguration(session: Session?, config: Config) {
        // Disable plane detection
        this.session = session
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


            // If  model haven't been placed yet and detected image has String identifier of "rabbit"
            // This is also example of model loading and placing at runtime
            if (!rabbitDetected && augmentedImage.name.equals("world")) {
                rabbitDetected = true
                Toast.makeText(this, "Image tag detected", Toast.LENGTH_LONG).show()
                anchorNode.worldScale = Vector3(1f, 1f, 1f)
                arFragment.arSceneView.scene.addChild(anchorNode)

                futures.add(ModelRenderable.builder()
                    .setSource(
                        this,
                        Uri.parse(cacheDir.path + "/model.glb")
                    )
                    .setIsFilamentGltf(true)
                    .build()
                    .thenAccept { model ->
                        val modelNode =
                            TransformableNode(arFragment.transformationSystem)
                        val rotation = modelNode.localRotation
                        val newRotation = Quaternion.axisAngle(Vector3(1f, 0f, 0f), -90f)
                        modelNode.localRotation = Quaternion.multiply(rotation, newRotation)

                        modelNode.localPosition = Vector3(0.0f, 0f, 0.06f)
                        modelNode.setRenderable(model).animate(true).start()
                        anchorNode.addChild(modelNode)

                    }
                    .exceptionally {
                        Toast.makeText(this, "Unable to load model", Toast.LENGTH_LONG)
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

                futures.add(
                    ViewRenderable.builder()
                        .setView(this, R.layout.image_view)
                        .build()
                        .thenAccept { view ->
                            val viewNode = TransformableNode(arFragment.transformationSystem)
                            viewNode.apply {

                                val newRotation = Quaternion.axisAngle(Vector3(2f, 0f, 0f), -90f)
                                localRotation = Quaternion.multiply(localRotation, newRotation)
                                localPosition = Vector3(0.0f, 0f, 0.0f)
                                localScale = Vector3(0.7f, 0.7f, 0.7f)
                                renderable = view
                                anchorNode.addChild(viewNode)
                            }

                        }
                        .exceptionally {
                            Toast.makeText(this, "Unable to load image", Toast.LENGTH_LONG).show()
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

    private fun downloadModel(url: String) {
        try {
            CoroutineScope(Dispatchers.IO).launch {
                val stream = URL(url).openStream()
                val output = FileOutputStream(File(cacheDir.absolutePath, "model.glb"))
                stream.copyTo(output)
                print("Model downloaded")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error downloading model.", Toast.LENGTH_LONG)
        }
    }
}