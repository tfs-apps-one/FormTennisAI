package tfsapps.formtennisai.analyzer;

import android.annotation.SuppressLint;
import android.media.Image;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions;

/**
 * CameraX {@link ImageAnalysis.Analyzer} that feeds each camera frame into
 * the ML Kit Accurate Pose Detector and delivers results via a callback.
 *
 * This is a direct port of
 * {@code tfsapps.formbaseballai.analyzer.PoseAnalyzer}: pose *detection* is
 * sport-agnostic (it just finds body landmarks in a frame), so nothing
 * about tennis changes this class's logic — only the package changed, to
 * live alongside the tennis-specific validator/scorer.
 *
 * Threading: analysis runs on the executor supplied to ImageAnalysis;
 * callbacks are delivered on the same background thread — post to main
 * thread in the listener if needed.
 */
public class TennisPoseAnalyzer implements ImageAnalysis.Analyzer {

    public interface Listener {
        /**
         * @param pose          The detected Pose (may have no landmarks if no person found).
         * @param imageWidth    Width of the input image (after rotation is applied).
         * @param imageHeight   Height of the input image (after rotation is applied).
         */
        void onPoseDetected(Pose pose, int imageWidth, int imageHeight);
    }

    private final PoseDetector poseDetector;
    private final Listener listener;

    public TennisPoseAnalyzer(@NonNull Listener listener) {
        this.listener = listener;

        AccuratePoseDetectorOptions options =
                new AccuratePoseDetectorOptions.Builder()
                        .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
                        .build();

        poseDetector = PoseDetection.getClient(options);
    }

    @Override
    @SuppressLint("UnsafeOptInUsageError")
    public void analyze(@NonNull ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        InputImage inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees);

        // Determine dimensions in the rotated coordinate space
        final int imgW, imgH;
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            imgW = imageProxy.getHeight();
            imgH = imageProxy.getWidth();
        } else {
            imgW = imageProxy.getWidth();
            imgH = imageProxy.getHeight();
        }

        poseDetector.process(inputImage)
                .addOnSuccessListener(pose -> listener.onPoseDetected(pose, imgW, imgH))
                .addOnFailureListener(e -> { /* silently skip bad frames */ })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    /** Release ML Kit resources when the camera is unbound. */
    public void shutdown() {
        poseDetector.close();
    }
}
